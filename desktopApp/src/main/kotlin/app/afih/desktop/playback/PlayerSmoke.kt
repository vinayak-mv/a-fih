package app.afih.desktop.playback

import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlinx.coroutines.*

/** Render a generated video inside the real Canvas, using the real HTTP bridge and libmpv. */
object PlayerSmoke {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val bytes = ByteArrayOutputStream().apply {
            write("YUV4MPEG2 W64 H64 F30:1 Ip A1:1 C420jpeg\n".toByteArray())
            repeat(60) { frame ->
                write("FRAME\n".toByteArray())
                write(ByteArray(64 * 64) { (32 + frame * 2).toByte() })
                write(ByteArray(64 * 64 / 2) { 128.toByte() })
            }
        }.toByteArray()
        val reads = AtomicInteger()
        val failure = AtomicReference<Throwable?>()
        val source = object : MediaByteSource {
            override val size = bytes.size.toLong()
            override suspend fun read(offset: Long, count: Int): ByteArray {
                reads.incrementAndGet()
                return bytes.copyOfRange(offset.toInt(), offset.toInt() + count)
            }
            override suspend fun close() = Unit
        }
        val windowId = CompletableFuture<Long>()
        val engine = AtomicReference<MpvEngine?>()
        val frame = AtomicReference<JFrame>()
        SwingUtilities.invokeAndWait {
            frame.set(JFrame("a-Fih embedding smoke").apply {
                add(MpvVideoCanvas({ windowId.complete(it) }, { engine.getAndSet(null)?.close() }))
                setSize(400, 260)
                isVisible = true
            })
        }
        try {
            StreamBridge(source) { failure.set(it) }.use { bridge ->
                val player = MpvEngine(windowId.get(10, TimeUnit.SECONDS), smoke = true)
                engine.set(player)
                player.command("loadfile", bridge.url, "replace")
                var state = PlaybackUi()
                var decoded = false
                var controlsChecked = false
                withTimeout(30_000) {
                    while (true) {
                        failure.get()?.let { throw it }
                        state = player.snapshot(state)
                        decoded = decoded || player.property("video-out-params/w") == "64"
                        if (!state.loading && !controlsChecked && state.positionMs >= 250) {
                            player.setProperty("pause", "yes")
                            check(player.property("pause") == "yes")
                            player.setProperty("volume", "35")
                            check(player.property("volume")?.toDoubleOrNull() == 35.0)
                            player.command("seek", "0.5", "absolute+exact")
                            player.setProperty("pause", "no")
                            controlsChecked = true
                        }
                        if (state.ended) break
                        delay(50)
                    }
                }
                check(decoded && controlsChecked && reads.get() > 0) { "Embedded video/control smoke did not complete." }
                // Exercise peer removal while libmpv is attached, using the production Canvas hook.
                SwingUtilities.invokeAndWait { frame.get().dispose() }
                check(engine.get() == null)
            }
        } finally {
            engine.getAndSet(null)?.close()
            SwingUtilities.invokeAndWait { frame.get().dispose() }
        }
        println("Bundled libmpv rendered embedded video over HTTP; pause, volume, seek and native cleanup passed.")
    }
}
