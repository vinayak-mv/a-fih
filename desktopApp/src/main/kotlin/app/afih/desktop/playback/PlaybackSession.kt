package app.afih.desktop.playback

import app.afih.desktop.userFacingFailure
import app.afih.telegram.IndexedMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owns one stream and engine; Compose owns the visible player screen. */
internal class PlaybackSession private constructor(
    val media: IndexedMessage, private val source: MediaByteSource,
    private val store: PlaybackStore, savedPosition: Long,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val mutableState = MutableStateFlow(PlaybackUi(positionMs = savedPosition))
    val state = mutableState.asStateFlow()
    private val bridge = StreamBridge(source) { failure -> fail(failure) }
    private var engine: MpvEngine? = null
    private var windowId: Long? = null
    private var monitor: Job? = null
    private var closed = false

    fun attach(id: Long) { scope.launch {
        lock.withLock {
            if (closed || windowId == id) return@withLock
            monitor?.cancel()
            engine?.close(); engine = null
            windowId = id
            try {
                val previous = state.value
                mutableState.update { it.copy(loading = true) }
                engine = MpvEngine(id, previous.positionMs)
                engine!!.setProperty("volume", previous.volume.toString())
                if (previous.paused) engine!!.setProperty("pause", "yes")
                previous.audio.firstOrNull { it.selected }?.let { engine!!.setProperty("aid", it.id) }
                previous.subtitles.firstOrNull { it.selected }?.let { engine!!.setProperty("sid", it.id) }
                engine!!.command("loadfile", bridge.url, "replace")
                monitor = scope.launch {
                    var ticks = 0
                    while (isActive) {
                        lock.withLock {
                            if (!closed) {
                                try {
                                    mutableState.update { current -> engine?.snapshot(current) ?: current }
                                    if (++ticks % 20 == 0) saveProgress()
                                } catch (failure: Exception) { fail(failure) }
                            }
                        }
                        delay(250)
                    }
                }
            } catch (failure: Exception) { engine?.close(); engine = null; fail(failure) }
        }
    } }

    /** Finish native use before AWT destroys a peer (including fullscreen recreation). */
    fun detach(id: Long) = runBlocking {
        lock.withLock {
            if (windowId == id) {
                monitor?.cancel(); monitor = null
                engine?.close(); engine = null; windowId = null
            }
        }
    }
    private fun fail(failure: Throwable) { mutableState.update { it.copy(loading = false, buffering = false, failure = userFacingFailure(failure)) } }
    private fun action(block: (MpvEngine) -> Unit) { scope.launch {
        lock.withLock {
            if (!closed) try { engine?.let(block) } catch (failure: Exception) { fail(failure) }
        }
    } }
    fun togglePause() = action { player ->
        if (state.value.ended) { player.command("seek", "0", "absolute+exact"); player.setProperty("pause", "no") }
        else player.setProperty("pause", if (state.value.paused) "no" else "yes")
    }
    fun seek(milliseconds: Long) = action { it.command("seek", (milliseconds.coerceAtLeast(0) / 1000.0).toString(), "absolute+exact") }
    fun skip(seconds: Int) = seek((state.value.positionMs + seconds * 1000L).coerceIn(0, state.value.durationMs.coerceAtLeast(0)))
    fun volume(value: Int) = action { it.setProperty("volume", value.coerceIn(0, 100).toString()) }
    fun audio(id: String) = action { it.setProperty("aid", id) }
    fun subtitles(id: String) = action { it.setProperty("sid", id) }
    fun retry() { scope.launch {
        val id = lock.withLock {
            if (closed) return@launch
            monitor?.cancel(); monitor = null
            engine?.close(); engine = null
            val previous = windowId; windowId = null
            mutableState.update { it.copy(loading = true, failure = null, ended = false) }
            previous
        }
        if (id != null) attach(id)
    } }
    private fun saveProgress() {
        val current = state.value
        if (!current.loading) runCatching { store.save(media.key, if (current.ended) current.durationMs else current.positionMs, current.durationMs) }
    }
    suspend fun close() {
        lock.withLock {
            if (closed) return
            closed = true; monitor?.cancel(); monitor = null
            saveProgress()
        }
        bridge.close()
        lock.withLock { engine?.close(); engine = null; windowId = null }
        source.close()
        scope.cancel()
    }
    companion object {
        fun open(media: IndexedMessage, source: MediaByteSource, store: PlaybackStore): PlaybackSession {
            NativePlayerRuntime.api // Fail before changing screens if the runtime is missing.
            return PlaybackSession(media, source, store, store.position(media.key))
        }
    }
}
