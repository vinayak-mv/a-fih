package app.afih.desktop.playback

import app.afih.desktop.telegram.*
import java.util.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** TDLib owns DC migration, file-reference recovery and chunk caching. Never read sparse holes. */
internal class TelegramByteSource(
    private val fileId: Long,
    override val size: Long,
    private val call: suspend (JsonObject) -> JsonObject,
) : MediaByteSource {
    private val lock = Mutex()
    @Volatile private var closed = false
    private var requestedThrough = 0L
    private var requestedFrom = 0L

    override suspend fun read(offset: Long, count: Int): ByteArray = lock.withLock {
        check(!closed)
        require(offset >= 0 && count in 1..262144 && offset < size)
        val needed = minOf(count.toLong(), size - offset).toInt()
        withTimeout(90_000) {
            fun prefixRequest() = request("getFileDownloadedPrefixSize") { put("file_id", fileId); put("offset", offset) }
            if (call(prefixRequest()).number("size") < needed) {
                if (offset < requestedFrom || offset + needed > requestedThrough) {
                    val window = minOf(4L * 1024 * 1024, size - offset)
                    call(request("downloadFile") {
                        put("file_id", fileId); put("priority", 32); put("offset", offset)
                        put("limit", window); put("synchronous", false)
                    })
                    requestedFrom = offset
                    requestedThrough = offset + window
                }
                while (call(prefixRequest()).number("size") < needed) {
                    check(!closed)
                    delay(100)
                }
            }
            val response = call(request("readFilePart") { put("file_id", fileId); put("offset", offset); put("count", needed) })
            Base64.getDecoder().decode(response.string("data")).also { check(it.size == needed) { "Incomplete Telegram media read." } }
        }
    }

    override suspend fun close() {
        closed = true
        // Stop first so a pending read can finish/cancel, then evict this temporary stream cache.
        runCatching { call(request("cancelDownloadFile") { put("file_id", fileId); put("only_if_pending", false) }) }
        lock.withLock { runCatching { call(request("deleteFile") { put("file_id", fileId) }) } }
    }
}
