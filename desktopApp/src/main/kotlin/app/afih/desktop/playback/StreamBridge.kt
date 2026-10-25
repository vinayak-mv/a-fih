package app.afih.desktop.playback

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.*

/** A single-player, loopback-only HTTP adapter. The random capability never contains credentials. */
internal class StreamBridge(private val source: MediaByteSource, private val onFailure: (Throwable) -> Unit) : AutoCloseable {
    private val token = "/${UUID.randomUUID()}/media"
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 8)
    private val executor = Executors.newFixedThreadPool(2) { task -> Thread(task, "afih-stream-http").apply { isDaemon = true } }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var active: Pair<Job, HttpExchange>? = null
    val url: String get() = "http://127.0.0.1:${server.address.port}$token"

    init {
        server.executor = executor
        server.createContext("/") { exchange ->
            if (exchange.requestURI.path != token || exchange.requestURI.rawQuery != null) {
                exchange.sendResponseHeaders(404, -1); exchange.close()
            } else if (exchange.requestMethod !in setOf("GET", "HEAD")) {
                exchange.responseHeaders.set("Allow", "GET, HEAD")
                exchange.sendResponseHeaders(405, -1); exchange.close()
            } else synchronized(this) {
                if (exchange.requestMethod == "GET") {
                    active?.first?.cancel()
                    active?.second?.close()
                }
                val job = scope.launch(start = CoroutineStart.LAZY) { serve(exchange) }
                if (exchange.requestMethod == "GET") active = job to exchange
                job.start()
            }
        }
        server.start()
    }

    private suspend fun serve(exchange: HttpExchange) {
        try {
            val rangeHeader = exchange.requestHeaders.getFirst("Range")
            val range = try { parseRange(rangeHeader, source.size) } catch (_: IllegalArgumentException) {
                exchange.responseHeaders.set("Content-Range", "bytes */${source.size}")
                exchange.sendResponseHeaders(416, -1)
                return
            }
            exchange.responseHeaders.apply {
                set("Accept-Ranges", "bytes")
                set("Content-Type", "application/octet-stream")
                set("Content-Length", range.length.toString())
                set("Cache-Control", "no-store")
                if (rangeHeader != null) set("Content-Range", "bytes ${range.first}-${range.last}/${source.size}")
            }
            val status = if (rangeHeader == null) 200 else 206
            if (exchange.requestMethod == "HEAD") { exchange.sendResponseHeaders(status, -1); return }
            exchange.sendResponseHeaders(status, range.length)
            var offset = range.first
            while (offset <= range.last) {
                currentCoroutineContext().ensureActive()
                val count = minOf(262144L, range.last - offset + 1).toInt()
                val bytes = try { source.read(offset, count) } catch (timeout: TimeoutCancellationException) {
                    onFailure(timeout); return
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) { onFailure(failure); return }
                check(bytes.size == count)
                exchange.responseBody.write(bytes)
                offset += bytes.size
            }
        } catch (_: CancellationException) {
            // Seeking supersedes an old request.
        } catch (_: java.io.IOException) {
            // The player closes old HTTP connections when seeking/stopping.
        } finally { exchange.close() }
    }

    @Synchronized override fun close() {
        active?.first?.cancel(); active?.second?.close(); active = null
        scope.cancel(); server.stop(0); executor.shutdownNow()
    }
}
