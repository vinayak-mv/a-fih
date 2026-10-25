package app.afih.desktop.metadata

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.time.Duration
import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jetbrains.skia.Image as SkiaImage

private object ArtworkCache {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build()
    private val connections = Semaphore(4)
    private val directory: Path get() = Path.of(System.getenv("LOCALAPPDATA"), "a-Fih", "artwork")

    suspend fun load(path: String, size: String): ImageBitmap? = connections.withPermit {
        if (!path.matches(Regex("/[A-Za-z0-9._-]+")) || size !in setOf("w185", "w300", "w500", "w780")) return@withPermit null
        val filename = MessageDigest.getInstance("SHA-256").digest("$size$path".toByteArray()).joinToString("") { "%02x".format(it) }
        val file = directory.resolve(filename)
        Files.createDirectories(directory)
        if (Files.exists(file)) {
            try { return@withPermit decode(Files.readAllBytes(file)) }
            catch (_: Exception) { Files.deleteIfExists(file) }
        }
        val query = HttpRequest.newBuilder(URI.create("https://image.tmdb.org/t/p/$size$path")).timeout(Duration.ofSeconds(15)).GET().build()
        val response = http.sendAsync(query, HttpResponse.BodyHandlers.ofInputStream()).await()
        val bytes = response.body().use { stream ->
            if (response.statusCode() != 200) return@withPermit null
            stream.readNBytes(8 * 1024 * 1024 + 1)
        }
        if (bytes.size > 8 * 1024 * 1024) return@withPermit null
        val bitmap = decode(bytes)
        val temporary = Files.createTempFile(directory, "image-", ".part")
        try { Files.write(temporary, bytes); Files.move(temporary, file, REPLACE_EXISTING) }
        finally { Files.deleteIfExists(temporary) }
        // Bound thumbnail storage; these are disposable artwork files, not downloads.
        runCatching { synchronized(this) { Files.list(directory).use { files ->
            val cached = files.filter { !it.fileName.toString().endsWith(".part") }.toList().sortedBy { Files.getLastModifiedTime(it).toMillis() }
            var total = cached.sumOf { Files.size(it) }
            for (old in cached) {
                if (total <= 200L * 1024 * 1024) break
                val length = Files.size(old)
                if (Files.deleteIfExists(old)) total -= length
            }
        } } }
        bitmap
    }

    private fun decode(bytes: ByteArray) = SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
}

@Composable
fun TmdbArtwork(path: String?, description: String, modifier: Modifier, size: String = "w300", placeholder: String = "") {
    val bitmap by produceState<ImageBitmap?>(null, path, size) {
        value = null
        if (path != null) {
            try { value = withContext(Dispatchers.IO) { ArtworkCache.load(path, size) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Keep a readable placeholder when artwork is unavailable. */ }
        }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) Image(image, description, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
        else if (placeholder.isNotBlank()) Text(placeholder, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
