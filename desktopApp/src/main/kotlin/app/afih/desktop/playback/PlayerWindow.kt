package app.afih.desktop.playback

import app.afih.desktop.userFacingFailure
import app.afih.telegram.IndexedMessage
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent

/** Heavyweight native video has its own window so it cannot cover Compose dialogs/controls. */
internal class PlayerWindow private constructor(
    private val media: IndexedMessage,
    private val source: MediaByteSource,
    private val store: PlaybackStore,
    private val savedPosition: Long,
    private val onClose: () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val component = EmbeddedMediaPlayerComponent(*NativePlayerRuntime.options)
    private val player get() = component.mediaPlayer()
    private val frame = JFrame("${media.fileName} · a-Fih")
    private val status = JLabel("Connecting to Telegram…")
    private val seek = JSlider(0, 10000, 0)
    private val time = JLabel("00:00 / 00:00")
    private val pause = JButton("Pause")
    private data class Track(val id: Int, val label: String) { override fun toString() = label }
    private val audio = JComboBox<Track>()
    private val subtitles = JComboBox<Track>()
    private var updating = false
    private var resumed = false
    private var ticks = 0
    @Volatile private var closed = false
    @Volatile private var failed = false
    private val bridge = StreamBridge(source) { failure ->
        failed = true
        SwingUtilities.invokeLater { if (!closed) status.text = "${userFacingFailure(failure)} Close and press Play to retry." }
    }
    private val timer = Timer(500) { refresh() }

    init {
        frame.defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        frame.addWindowListener(object : WindowAdapter() {
            override fun windowClosing(event: WindowEvent) { onClose() }
        })
        frame.minimumSize = Dimension(800, 500)
        frame.setSize(1100, 760)
        frame.setLocationRelativeTo(null)
        component.background = Color.BLACK
        frame.add(component, BorderLayout.CENTER)
        val controls = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); border = BorderFactory.createEmptyBorder(10, 14, 12, 14) }
        controls.add(status)
        controls.add(seek)
        val buttons = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT))
        pause.addActionListener { if (player.status().isPlaying) player.controls().pause() else player.controls().play() }
        seek.addChangeListener { if (!updating && !seek.valueIsAdjusting && player.status().isSeekable) player.controls().setPosition(seek.value / 10000f) }
        buttons.add(pause)
        buttons.add(JButton("Restart").apply { addActionListener { player.controls().setTime(0) } })
        buttons.add(time)
        buttons.add(JLabel("Volume"))
        buttons.add(JSlider(0, 100, 80).apply { preferredSize = Dimension(100, 26); addChangeListener { player.audio().setVolume(value) } })
        controls.add(buttons)
        val tracks = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT))
        audio.preferredSize = Dimension(200, 28); subtitles.preferredSize = Dimension(200, 28)
        tracks.add(JLabel("Audio")); tracks.add(audio)
        tracks.add(JLabel("Subtitles")); tracks.add(subtitles)
        audio.maximumRowCount = 12; subtitles.maximumRowCount = 12
        audio.addActionListener { if (!updating) (audio.selectedItem as? Track)?.let { player.audio().setTrack(it.id) } }
        subtitles.addActionListener { if (!updating) (subtitles.selectedItem as? Track)?.let { player.subpictures().setTrack(it.id) } }
        tracks.add(JButton("Maximize").apply { addActionListener { frame.extendedState = if (frame.extendedState == JFrame.MAXIMIZED_BOTH) JFrame.NORMAL else JFrame.MAXIMIZED_BOTH } })
        controls.add(tracks)
        frame.add(controls, BorderLayout.SOUTH)
        player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun error(mediaPlayer: MediaPlayer) {
                failed = true
                SwingUtilities.invokeLater { if (!closed) status.text = "Playback failed. Check the connection and source access, then close and press Play to retry." }
            }
            override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) {
                SwingUtilities.invokeLater { if (!closed && !failed) status.text = if (newCache < 100) "Buffering… ${newCache.toInt()}%" else media.fileName }
            }
            override fun finished(mediaPlayer: MediaPlayer) {
                SwingUtilities.invokeLater { if (!closed) { status.text = "Finished"; storeProgress() } }
            }
        })
    }

    private fun start() {
        frame.isVisible = true
        player.audio().setVolume(80)
        check(player.media().play(bridge.url, ":network-caching=1500")) { "Player could not open this stream." }
        timer.start()
    }

    private fun refresh() {
        if (closed) return
        val length = player.status().length()
        val position = player.status().time().coerceAtLeast(0)
        if (length > 0 && player.status().isSeekable && !resumed) {
            resumed = true
            if (savedPosition > 0 && savedPosition < length * .95) player.controls().setTime(savedPosition)
        }
        updating = true
        try {
            if (!seek.valueIsAdjusting && length > 0) seek.value = (position.toDouble() / length * 10000).toInt().coerceIn(0, 10000)
            seek.isEnabled = player.status().isSeekable
            pause.text = if (player.status().isPlaying) "Pause" else "Play"
            time.text = "${clock(position)} / ${clock(length)}"
            if (++ticks % 4 == 0) {
                val audioTracks = player.audio().trackDescriptions().orEmpty().map { Track(it.id(), it.description()) }
                val subtitleTracks = player.subpictures().trackDescriptions().orEmpty().map { Track(it.id(), it.description()) }
                updateTracks(audio, audioTracks, player.audio().track())
                updateTracks(subtitles, subtitleTracks, player.subpictures().track())
            }
        } finally { updating = false }
        if (ticks % 10 == 0 && resumed) storeProgress()
    }

    private fun updateTracks(box: JComboBox<Track>, tracks: List<Track>, selected: Int) {
        if ((0 until box.itemCount).map { box.getItemAt(it) } != tracks) box.model = DefaultComboBoxModel(tracks.toTypedArray())
        tracks.firstOrNull { it.id == selected }?.let { box.selectedItem = it }
    }
    private fun storeProgress() {
        if (!resumed) return
        val position = player.status().time()
        val length = player.status().length()
        scope.launch { runCatching { store.save(media.key, position, length) } }
    }
    suspend fun close() {
        val shouldClose = withContext(Dispatchers.Swing) {
            if (closed) false else { storeProgress(); closed = true; timer.stop(); frame.dispose(); true }
        }
        if (!shouldClose) return
        bridge.close()
        withContext(Dispatchers.IO) { component.release(); source.close() }
        scope.coroutineContext[Job]?.children?.toList()?.joinAll()
        scope.cancel()
    }

    companion object {
        suspend fun open(media: IndexedMessage, source: MediaByteSource, store: PlaybackStore, onClose: () -> Unit): PlayerWindow {
            NativePlayerRuntime.prepare()
            val position = store.position(media.key)
            val window = withContext(Dispatchers.Swing) { PlayerWindow(media, source, store, position, onClose) }
            try {
                withContext(Dispatchers.Swing) { window.start() }
                return window
            } catch (failure: Throwable) { window.close(); throw failure }
        }
        private fun clock(milliseconds: Long): String {
            val seconds = milliseconds.coerceAtLeast(0) / 1000
            return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60) else "%02d:%02d".format(seconds / 60, seconds % 60)
        }
    }
}
