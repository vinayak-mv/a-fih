package app.afih.desktop.telegram

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*

internal interface TdJsonApi : Library {
    fun td_json_client_create(): Pointer
    fun td_json_client_send(client: Pointer, request: String)
    fun td_json_client_receive(client: Pointer, timeout: Double): Pointer?
    fun td_json_client_destroy(client: Pointer)
    fun td_json_client_execute(client: Pointer?, request: String): Pointer?
}

internal fun JsonObject.string(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
internal fun JsonObject.number(key: String): Long = (get(key) as? JsonPrimitive)?.longOrNull ?: 0L
internal fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject
internal fun request(type: String, fields: JsonObjectBuilder.() -> Unit = {}): JsonObject = buildJsonObject {
    put("@type", type)
    fields()
}

class TelegramFailure(message: String, val kind: String = "TELEGRAM", val retryable: Boolean = false, val retryAfterMs: Long = 0) : Exception(message)

/** Retry only transient failures; cancellation and permission/missing errors never retry. */
internal suspend fun <T> telegramRetry(wait: suspend (Long) -> Unit = { delay(it) }, operation: suspend () -> T): T {
    var attempt = 0
    while (true) {
        try { return operation() }
        catch (failure: Exception) {
            if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
            val transient = failure is TimeoutCancellationException || (failure is TelegramFailure && failure.retryable && failure.retryAfterMs == 0L)
            if (!transient || attempt >= 2) throw failure
            wait(1000L shl attempt++)
        }
    }
}

/** Never display raw TDLib errors: they can include request values. */
internal fun telegramFailure(error: JsonObject): TelegramFailure {
    val code = error.number("code")
    val detail = error.string("message")
    val retryAfter = if (code == 429L) (Regex("(?i)(?:retry after |FLOOD_WAIT_)([0-9]+)").find(detail)?.groupValues?.get(1)?.toLongOrNull() ?: 60L).coerceIn(1,86400) * 1000 else 0L
    val kind = when { code == 429L -> "RATE_LIMIT"; code == 401L || detail.contains("TOKEN",true) || detail.contains("API_ID",true) || detail.contains("API_HASH",true) -> "AUTH"; code == 403L || detail.contains("CHANNEL_PRIVATE") || detail.contains("USER_NOT_PARTICIPANT") -> "ACCESS"; code == 404L || detail.contains("MESSAGE_ID_INVALID") -> "MISSING"; code >= 500 -> "NETWORK"; else -> "TELEGRAM" }
    return TelegramFailure(when {
        code == 406L -> "Telegram could not complete this request."
        code == 429L -> "Telegram requested a pause. Wait before retrying this operation."
        detail.contains("TOKEN", ignoreCase = true) -> "Telegram rejected the bot token. Check the provider credentials."
        detail.contains("API_ID", ignoreCase = true) || detail.contains("API_HASH", ignoreCase = true) -> "Telegram rejected the API ID or hash."
        detail.contains("Supergroup not found", ignoreCase = true) -> "Telegram could not resolve this chat. Check the channel/group ID and bot access."
        detail.contains("CHANNEL_PRIVATE") || detail.contains("CHAT_NOT_FOUND") || detail.contains("CHAT_ID_INVALID") || detail.contains("USER_NOT_PARTICIPANT") || detail.contains("CHANNEL_INVALID") -> "The bot cannot access this chat. Check the chat and bot permissions."
        code == 401L -> "Telegram authorization is no longer valid. Reconnect the provider."
        code == 403L -> "Telegram denied access to this source. Check the bot permissions."
        code == 404L -> "Telegram could not find this chat or message."
        else -> "Telegram could not complete this request (code $code). Retry or check the source permissions."
    }, kind, code >= 500 || code == 429L, retryAfter)
}

/** One receive thread per legacy JSON client; requests are correlated using @extra. */
internal class TdJsonClient(private val onThrottle: (Long) -> Unit = {}, private val onUpdate: suspend (JsonObject) -> Unit) : AutoCloseable {
    private val api: TdJsonApi = loadLibrary()
    private val client = api.td_json_client_create()
    private val sequence = AtomicLong()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private val updates = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val updateQueue = Channel<JsonObject>(Channel.UNLIMITED)
    private val updateWorker = updates.launch { for (event in updateQueue) onUpdate(event) }
    @Volatile private var closing = false
    @Volatile private var closed = false
    @Volatile private var blockedUntil = 0L
    private val receiver = Thread({ receiveLoop() }, "afih-telegram-receive").apply { isDaemon = true; start() }

    init {
        // TDLib logging is disabled to keep credentials and chat content out of logs.
        api.td_json_client_execute(null, request("setLogVerbosityLevel") { put("new_verbosity_level", 0) }.toString())
    }

    val isOpen: Boolean get() = !closing && !closed

    suspend fun call(query: JsonObject): JsonObject = telegramRetry {
        val wait = blockedUntil - System.currentTimeMillis()
        if(wait > 0) throw TelegramFailure("Telegram requested a pause. Wait before retrying this operation.","RATE_LIMIT",true,wait)
        try { callOnce(query) } catch (failure: TelegramFailure) {
            if (failure.retryAfterMs > 0) { blockedUntil = System.currentTimeMillis()+failure.retryAfterMs; onThrottle(failure.retryAfterMs) }
            throw failure
        }
    }

    private suspend fun callOnce(query: JsonObject): JsonObject {
        val key = sequence.incrementAndGet().toString()
        val answer = CompletableDeferred<JsonObject>()
        try {
            synchronized(this) {
                check(!closing && !closed) { "Telegram connection is closed." }
                pending[key] = answer
                api.td_json_client_send(client, JsonObject(query + ("@extra" to JsonPrimitive(key))).toString())
            }
            val response = withTimeout(90_000) { answer.await() }
            if (response.string("@type") == "error") throw telegramFailure(response)
            return response
        } finally {
            pending.remove(key)
        }
    }

    private fun receiveLoop() {
        try {
            while (!closed) {
                val pointer = api.td_json_client_receive(client, 0.5) ?: continue
                // Copy before the next native receive invalidates the pointer.
                val event = Json.parseToJsonElement(pointer.getString(0, "UTF-8")).jsonObject
                val extra = event.string("@extra")
                if (extra.isNotEmpty()) pending.remove(extra)?.complete(event)
                else updateQueue.trySend(event)
                if (event.string("@type") == "updateAuthorizationState" && event.obj("authorization_state")?.string("@type") == "authorizationStateClosed") closed = true
            }
        } catch (_: Exception) {
            pending.values.forEach { it.completeExceptionally(TelegramFailure("Telegram connection was interrupted. Reconnect and retry.", "NETWORK", true)) }
        } finally {
            synchronized(this) { closed = true }
            pending.values.forEach { it.completeExceptionally(TelegramFailure("Telegram connection is closed.")) }
            pending.clear()
            updateQueue.close()
            // Drain received edit/delete work into the app journal on a normal close.
            runBlocking { withTimeoutOrNull(10_000) { updateWorker.join() } }
            updates.cancel()
            api.td_json_client_destroy(client)
        }
    }

    override fun close() {
        synchronized(this) {
            if (!closing && !closed) {
                closing = true
                api.td_json_client_send(client, request("close").toString())
            }
        }
        // The receive thread alone destroys the pointer after Closed; never race receive.
        receiver.join(10_000)
        if (receiver.isAlive) throw TelegramFailure("Telegram is still closing its session. Wait a moment before retrying.")
    }

    companion object {
        private val api by lazy {
            val libraryOverride = System.getenv("AFIH_TDJSON_PATH")
            val packaged = System.getProperty("compose.application.resources.dir")?.let { Path.of(it, "tdjson.dll") }
            val local = Path.of("native", "windows-x64", "tdjson.dll").toAbsolutePath()
            val library = libraryOverride?.let { Path.of(it) } ?: packaged?.takeIf { Files.isRegularFile(it) } ?: local
            if (!Files.isRegularFile(library)) throw TelegramFailure("Telegram runtime is missing. Use the complete Windows artifact from the latest build.")
            try {
                Native.load(library.toAbsolutePath().toString(), TdJsonApi::class.java, mapOf(Library.OPTION_STRING_ENCODING to "UTF-8"))
            } catch (_: UnsatisfiedLinkError) {
                throw TelegramFailure("Telegram runtime could not load. Extract the complete Windows artifact and use a 64-bit Windows system.")
            }
        }
        private fun loadLibrary(): TdJsonApi = api
    }
}
