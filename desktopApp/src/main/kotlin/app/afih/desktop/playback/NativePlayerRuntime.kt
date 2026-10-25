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

/** CI creates/releases the actual native engine, without Telegram credentials or a visible window. */
object PlayerSmoke {
    @JvmStatic fun main(args: Array<String>) {
        NativePlayerRuntime.prepare()
        val factory = MediaPlayerFactory(*NativePlayerRuntime.options, "--aout=dummy", "--vout=dummy")
        val player = factory.mediaPlayers().newMediaPlayer()
        val fixture = Files.createTempFile("afih-player-smoke", ".wav")
        try {
            val format = AudioFormat(8000f, 16, 1, true, false)
            AudioInputStream(ByteArrayInputStream(ByteArray(16000)), format, 8000).use {
                AudioSystem.write(it, AudioFileFormat.Type.WAVE, fixture.toFile())
            }
            val finished = CompletableFuture<Unit>()
            player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
                override fun finished(mediaPlayer: MediaPlayer) { finished.complete(Unit) }
                override fun error(mediaPlayer: MediaPlayer) { finished.completeExceptionally(IllegalStateException("Native playback failed.")) }
            })
            check(player.media().play(fixture.toString()))
            finished.get(20, TimeUnit.SECONDS)
        } finally { player.release(); factory.release(); Files.deleteIfExists(fixture) }
        println("Bundled LibVLC decoded a local WAV and shut down successfully.")
    }
}
