package app.afih.desktop.telegram

import app.afih.model.ParsingMode
import app.afih.model.TelegramProvider
import app.afih.telegram.IndexedMessage
import app.afih.telegram.MessageKey
import com.sun.jna.platform.win32.Crypt32Util
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*

internal class TelegramSession(
    private val provider: TelegramProvider,
    private val directory: Path,
) : AutoCloseable {
    private val ready = CompletableDeferred<Unit>()
    private lateinit var client: TdJsonClient

    suspend fun connect() {
        client = TdJsonClient { update ->
            if (update.string("@type") != "updateAuthorizationState") return@TdJsonClient
            try {
                when (update.obj("authorization_state")?.string("@type")) {
                    "authorizationStateWaitTdlibParameters" -> initialize()
                    "authorizationStateWaitPhoneNumber" -> client.call(request("checkAuthenticationBotToken") { put("token", provider.credentials.botToken) })
                    "authorizationStateReady" -> ready.complete(Unit)
                    "authorizationStateClosed" -> ready.completeExceptionally(TelegramFailure("Telegram connection closed during sign-in."))
                    "authorizationStateWaitCode", "authorizationStateWaitPassword", "authorizationStateWaitRegistration" -> ready.completeExceptionally(TelegramFailure("This connection requires a bot token, not a Telegram user account."))
                }
            } catch (cancelled: CancellationException) {
                ready.completeExceptionally(cancelled)
                throw cancelled
            } catch (failure: Exception) {
                ready.completeExceptionally(failure)
            }
        }
        // TDLib emits its first state only after a request.
        client.call(request("getAuthorizationState"))
        withTimeout(120_000) { ready.await() }
        val user = client.call(request("getMe"))
        if (user.obj("type")?.string("@type") != "userTypeBot") throw TelegramFailure("This session is not a bot account.")
        if (user.number("id").toString() != provider.credentials.botToken.substringBefore(':')) throw TelegramFailure("The saved session belongs to a different bot. Remove and recreate this provider.")
    }

    private suspend fun initialize() {
        Files.createDirectories(directory)
        val keyFile = directory.resolve("session-key.dpapi")
        val databaseKey = if (Files.exists(keyFile)) Crypt32Util.cryptUnprotectData(Files.readAllBytes(keyFile)) else {
            // Never replace a lost key for an existing database.
            check(!Files.exists(directory.resolve("db"))) { "Saved Telegram session key is missing." }
            ByteArray(32).also { bytes ->
                SecureRandom().nextBytes(bytes)
                Files.write(keyFile, Crypt32Util.cryptProtectData(bytes), CREATE_NEW)
            }
        }
        try {
            client.call(request("setTdlibParameters") {
                put("use_test_dc", false)
                put("database_directory", directory.resolve("db").toString())
                put("files_directory", directory.resolve("cache").toString())
                put("database_encryption_key", Base64.getEncoder().encodeToString(databaseKey))
                put("use_file_database", true)
                put("use_chat_info_database", true)
                put("use_message_database", true)
                put("use_secret_chats", false)
                put("api_id", provider.credentials.apiId)
                put("api_hash", provider.credentials.apiHash)
                put("system_language_code", "en")
                put("device_model", "a-Fih Windows")
                put("system_version", System.getProperty("os.version"))
                put("application_version", "0.2.0")
            })
        } finally { databaseKey.fill(0) }
    }

    suspend fun resolveChat(reference: String): Pair<String, String> = resolveTelegramChat(reference, client::call)

    suspend fun fetch(chat: String, ids: List<Long>, parsingMode: ParsingMode): List<IndexedMessage> {
        val response = client.call(request("getMessages") {
            put("chat_id", chat.toLong())
            putJsonArray("message_ids") { ids.forEach { add(toTdMessageId(it)) } }
        })
        return (response["messages"] as? JsonArray).orEmpty().mapNotNull { element ->
            (element as? JsonObject)?.let { decodeMedia(provider.id, chat, it, parsingMode) }
        }
    }

    override fun close() { if (::client.isInitialized) client.close() }
}

/** The pinned TDLib MessageId.h shifts raw server IDs by 20 bits. */
internal fun toTdMessageId(raw: Long): Long {
    require(raw in 1..Int.MAX_VALUE.toLong())
    return raw shl 20
}

internal fun fromTdMessageId(id: Long): Long {
    require(id > 0 && (id and ((1L shl 20) - 1)) == 0L) { "Unsupported Telegram message identifier." }
    return (id shr 20).also { require(it <= Int.MAX_VALUE) }
}

internal fun decodeMedia(providerId: String, chat: String, message: JsonObject, parsing: ParsingMode): IndexedMessage? {
    require(message.number("chat_id").toString() == chat) { "Telegram returned a message from a different chat." }
    val content = message.obj("content") ?: return null
    val kind = when (content.string("@type")) { "messageVideo" -> "video"; "messageDocument" -> "document"; "messageAudio" -> "audio"; else -> return null }
    val descriptor = content.obj(kind) ?: return null
    val file = descriptor.obj(kind) ?: return null
    val fileName = descriptor.string("file_name").ifBlank { "Telegram media ${fromTdMessageId(message.number("id"))}" }
    val mime = descriptor.string("mime_type")
    val extension = fileName.substringAfterLast('.', "").lowercase()
    if (kind == "document" && !mime.startsWith("video/") && !mime.startsWith("audio/") && extension !in setOf("mkv", "mp4", "avi", "mov", "webm", "m4v", "mp3", "flac", "m4a", "aac", "ogg", "wav", "opus")) return null
    val topic = message.obj("topic_id")
    val topicId = when (topic?.string("@type")) {
        "messageTopicForum" -> topic?.number("forum_topic_id")?.takeIf { it > 0 }
        "messageTopicThread" -> topic?.number("message_thread_id")?.takeIf { it > 0 }?.let(::fromTdMessageId)
        else -> message.number("message_thread_id").takeIf { it > 0 }?.let(::fromTdMessageId)
    }
    val caption = content.obj("caption")?.string("text").orEmpty()
    val fileTitle = fileName.substringBeforeLast('.', fileName).replace(Regex("[._]+"), " ").trim()
    val captionTitle = caption.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    val title = when (parsing) { ParsingMode.FILE_NAME -> fileTitle; ParsingMode.CAPTION -> captionTitle.ifBlank { fileTitle }; ParsingMode.BOTH -> fileTitle.ifBlank { captionTitle } }
    return IndexedMessage(
        MessageKey(providerId, chat, fromTdMessageId(message.number("id"))), fileName, topicId, caption,
        mime, file.number("size"), file.obj("remote")?.string("id").orEmpty(), title.ifBlank { fileName },
    )
}
