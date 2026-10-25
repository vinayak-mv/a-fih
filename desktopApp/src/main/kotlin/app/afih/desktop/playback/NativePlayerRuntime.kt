package app.afih.desktop.playback

import com.sun.jna.NativeLibrary
import java.nio.file.Files
import java.nio.file.Path
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.sound.sampled.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger

internal object NativePlayerRuntime {
    @Synchronized fun prepare() {
        val packaged = System.getProperty("compose.application.resources.dir")?.let { Path.of(it, "vlc") }
        val directory = packaged?.takeIf { Files.isRegularFile(it.resolve("libvlc.dll")) }
            ?: Path.of("native", "windows-x64", "vlc").toAbsolutePath()
        check(Files.isRegularFile(directory.resolve("libvlc.dll")) && Files.isDirectory(directory.resolve("plugins"))) {
            "Player runtime is missing. Extract the complete Windows preview artifact."
        }
        // Load dependencies from the bundled directory, without changing the system PATH.
        NativeLibrary.addSearchPath("libvlccore", directory.toString())
        NativeLibrary.addSearchPath("libvlc", directory.toString())
        NativeLibrary.getInstance(directory.resolve("libvlccore.dll").toString())
    }
    val options = arrayOf("--quiet", "--no-video-title-show", "--no-metadata-network-access", "--no-lua")
}

/** CI plays through the actual HTTP bridge/engine, without credentials or a visible window. */
object PlayerSmoke {
    @JvmStatic fun main(args: Array<String>) {
        NativePlayerRuntime.prepare()
        val factory = MediaPlayerFactory(*NativePlayerRuntime.options, "--aout=dummy", "--vout=dummy")
        val player = factory.mediaPlayers().newMediaPlayer()
        try {
            val format = AudioFormat(8000f, 16, 1, true, false)
            val bytes = ByteArrayOutputStream().also { output ->
                AudioInputStream(ByteArrayInputStream(ByteArray(16000)), format, 8000).use {
                    AudioSystem.write(it, AudioFileFormat.Type.WAVE, output)
                }
            }.toByteArray()
            val reads = AtomicInteger()
            val source = object : MediaByteSource {
                override val size = bytes.size.toLong()
                override suspend fun read(offset: Long, count: Int): ByteArray {
                    reads.incrementAndGet()
                    return bytes.copyOfRange(offset.toInt(), offset.toInt() + count)
                }
                override suspend fun close() = Unit
            }
            val finished = CompletableFuture<Unit>()
            player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
                override fun finished(mediaPlayer: MediaPlayer) { finished.complete(Unit) }
                override fun error(mediaPlayer: MediaPlayer) { finished.completeExceptionally(IllegalStateException("Native playback failed.")) }
            })
            StreamBridge(source) { finished.completeExceptionally(it) }.use { bridge ->
                check(player.media().play(bridge.url))
                finished.get(20, TimeUnit.SECONDS)
                check(reads.get() > 0)
            }
        } finally { player.release(); factory.release() }
        println("Bundled LibVLC decoded a WAV through the loopback stream bridge and shut down successfully.")
    }
}
