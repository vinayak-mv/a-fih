package app.afih.desktop.playback

import app.afih.desktop.telegram.*
import app.afih.telegram.MessageKey
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class StreamingTest {
    @Test fun byteRangesHandleLargeFilesSuffixesAndClamping() {
        val size = 6_000_000_000L
        assertEquals(ByteRange(0, size - 1), parseRange(null, size))
        assertEquals(ByteRange(4_000_000_000, size - 1), parseRange("bytes=4000000000-", size))
        assertEquals(ByteRange(size - 500, size - 1), parseRange("bytes=-500", size))
        assertEquals(ByteRange(0, 99), parseRange("bytes=-500", 100))
        assertEquals(ByteRange(20, 99), parseRange("bytes=20-999", 100))
    }
    @Test fun invalidAndUnsatisfiableRangesAreRejected() {
        listOf("bytes=100-", "bytes=3-2", "bytes=-0", "bytes=-", "bytes=0-1,4-5", "bytes=-1-5", "items=1-2", "bytes=0-9223372036854775808").forEach {
            assertFailsWith<IllegalArgumentException>(it) { parseRange(it, 100) }
        }
    }
    @Test fun telegramReadsWaitForActualDownloadedPrefixAndKeepLongOffsets() = runBlocking {
        val queries = mutableListOf<JsonObject>()
        var prefixChecks = 0
        val source = TelegramByteSource(17, 6_000_000_000L) { query ->
            queries += query
            when (query.string("@type")) {
                "getFileDownloadedPrefixSize" -> buildJsonObject { put("size", if (++prefixChecks == 1) 0 else 4) }
                "readFilePart" -> buildJsonObject { put("data", Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4))) }
                else -> buildJsonObject {}
            }
        }
        assertContentEquals(byteArrayOf(1, 2, 3, 4), source.read(4_000_000_000L, 4))
        val download = queries.single { it.string("@type") == "downloadFile" }
        assertEquals(4_000_000_000L, download.number("offset"))
        assertEquals(4_194_304L, download.number("limit"))
        source.close()
        assertEquals(listOf("cancelDownloadFile", "deleteFile"), queries.takeLast(2).map { it.string("@type") })
    }
    @Test fun shortTelegramReadsAreNotReturnedAsValidContent() = runBlocking {
        val source = TelegramByteSource(1, 10) { query ->
            if (query.string("@type") == "getFileDownloadedPrefixSize") buildJsonObject { put("size", 10) }
            else buildJsonObject { put("data", "AQ==") }
        }
        assertFailsWith<IllegalStateException> { source.read(0, 4) }
    }
    @Test fun bridgeServesRangesHeadAndRejectsBadRequests() {
        val reads = mutableListOf<Pair<Long, Int>>()
        val source = object : MediaByteSource {
            override val size = 100L
            override suspend fun read(offset: Long, count: Int): ByteArray {
                synchronized(reads) { reads += offset to count }
                return ByteArray(count) { (offset + it).toByte() }
            }
            override suspend fun close() = Unit
        }
        StreamBridge(source) { throw AssertionError(it) }.use { bridge ->
            val client = HttpClient.newHttpClient()
            fun send(range: String? = null, method: String = "GET", url: String = bridge.url): HttpResponse<ByteArray> {
                val request = HttpRequest.newBuilder(URI(url)).method(method, HttpRequest.BodyPublishers.noBody())
                range?.let { request.header("Range", it) }
                return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray())
            }
            val partial = send("bytes=10-19")
            assertEquals(206, partial.statusCode())
            assertEquals("bytes 10-19/100", partial.headers().firstValue("Content-Range").orElseThrow())
            assertContentEquals(ByteArray(10) { (it + 10).toByte() }, partial.body())
            assertEquals(200, send(method = "HEAD").statusCode())
            assertEquals(listOf(10L to 10), reads)
            assertEquals(416, send("bytes=100-").statusCode())
            assertEquals(405, send(method = "POST").statusCode())
            assertEquals(404, send(url = bridge.url + "/wrong").statusCode())
            assertContentEquals(byteArrayOf(98, 99), send("bytes=-2").body())
        }
    }
    @Test fun resumeStateIsPerProviderChatAndMessageAndCompletedItemsRestart() {
        val directory = Files.createTempDirectory("afih-progress-test")
        try {
            val key = MessageKey("provider", "-1000000000123", 7)
            PlaybackStore(directory.resolve("media.sqlite")).save(key, 40_000, 100_000)
            val reopened = PlaybackStore(directory.resolve("media.sqlite"))
            assertEquals(40_000L, reopened.position(key))
            assertEquals(0L, reopened.position(key.copy(providerId = "other")))
            reopened.save(key, 98_000, 100_000)
            assertEquals(0L, reopened.position(key))
        } finally { Files.walk(directory).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }
}
