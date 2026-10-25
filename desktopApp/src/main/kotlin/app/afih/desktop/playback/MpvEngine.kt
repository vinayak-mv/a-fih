package app.afih.desktop.playback

import com.sun.jna.*
import java.nio.file.Files
import java.nio.file.Path

/** Stable libmpv client ABI; strings are UTF-8, command arguments are null-terminated. */
internal interface MpvApi : Library {
    fun mpv_create(): Pointer?
    fun mpv_set_option_string(handle: Pointer, name: String, value: String): Int
    fun mpv_initialize(handle: Pointer): Int
    fun mpv_command(handle: Pointer, arguments: Pointer): Int
    fun mpv_set_property_string(handle: Pointer, name: String, value: String): Int
    fun mpv_get_property_string(handle: Pointer, name: String): Pointer?
    fun mpv_free(pointer: Pointer)
    fun mpv_wait_event(handle: Pointer, timeout: Double): Pointer
    fun mpv_terminate_destroy(handle: Pointer)
}

@Structure.FieldOrder("eventId", "error", "replyUserdata", "data")
internal class MpvEvent(pointer: Pointer) : Structure(pointer) {
    @JvmField var eventId: Int = 0
    @JvmField var error: Int = 0
    @JvmField var replyUserdata: Long = 0
    @JvmField var data: Pointer? = null
    init { read() }
}

internal data class PlaybackTrack(val id: String, val label: String, val selected: Boolean)
internal data class PlaybackUi(
    val positionMs: Long = 0, val durationMs: Long = 0, val paused: Boolean = false,
    val loading: Boolean = true, val buffering: Boolean = false, val seekable: Boolean = false,
    val ended: Boolean = false, val volume: Int = 80,
    val audio: List<PlaybackTrack> = emptyList(), val subtitles: List<PlaybackTrack> = emptyList(),
    val failure: String? = null,
)

internal object NativePlayerRuntime {
    val api: MpvApi by lazy {
        val packaged = System.getProperty("compose.application.resources.dir")?.let { Path.of(it, "mpv", "libmpv-2.dll") }
        val library = packaged?.takeIf { Files.isRegularFile(it) }
            ?: Path.of("native", "windows-x64", "mpv", "libmpv-2.dll").toAbsolutePath()
        if (!Files.isRegularFile(library)) throw PlaybackFailure("Player runtime is missing. Extract the complete Windows preview artifact.")
        try { Native.load(library.toString(), MpvApi::class.java, mapOf(Library.OPTION_STRING_ENCODING to "UTF-8")) }
        catch (_: LinkageError) { throw PlaybackFailure("The bundled mpv runtime could not load. Extract the complete Windows preview artifact and retry.") }
    }
}

/** Video is parented to the app's Canvas HWND. This engine never opens a player window. */
internal class MpvEngine(
    windowId: Long?, startMs: Long = 0, private val api: MpvApi = NativePlayerRuntime.api,
    smoke: Boolean = false,
) : AutoCloseable {
    private var handle: Pointer? = api.mpv_create() ?: throw PlaybackFailure("mpv could not initialize.")
    private var loaded = false
    private var ended = false
    private var tracks = emptyList<Pair<String, PlaybackTrack>>()
    private var polls = 0
    init {
        try {
            listOf("config" to "no", "load-scripts" to "no", "osc" to "no", "terminal" to "no",
                "msg-level" to "all=no", "input-default-bindings" to "no", "input-vo-keyboard" to "no",
                "ytdl" to "no", "keep-open" to "yes", "idle" to "yes", "volume" to "80",
                "cache" to "yes", "demuxer-max-bytes" to "32MiB", "demuxer-max-back-bytes" to "8MiB",
                "hwdec" to "auto-safe").forEach { (key, value) -> option(key, value) }
            if (startMs > 0) option("start", (startMs / 1000.0).toString())
            if (windowId != null) {
                option("wid", (windowId and 0xffffffffL).toString())
                option("vo", "gpu")
                option("gpu-api", "d3d11")
                if (smoke) option("d3d11-warp", "yes")
            } else option("vo", "null")
            if (smoke) option("ao", "null")
            checkResult(api.mpv_initialize(handle!!))
        } catch (failure: Throwable) { close(); throw failure }
    }
    private fun checkResult(result: Int) { if (result < 0) throw PlaybackFailure("mpv could not complete the playback request. Retry this file.") }
    private fun option(name: String, value: String) { checkResult(api.mpv_set_option_string(handle!!, name, value)) }
    @Synchronized fun command(vararg arguments: String) {
        val current = handle ?: return
        val array = StringArray(arguments, "UTF-8")
        checkResult(api.mpv_command(current, array))
    }
    @Synchronized fun property(name: String): String? {
        val current = handle ?: return null
        val pointer = api.mpv_get_property_string(current, name) ?: return null
        return try { pointer.getString(0, "UTF-8") } finally { api.mpv_free(pointer) }
    }
    @Synchronized fun setProperty(name: String, value: String) {
        val current = handle ?: return
        checkResult(api.mpv_set_property_string(current, name, value))
    }
    @Synchronized fun snapshot(previous: PlaybackUi): PlaybackUi {
        val current = handle ?: return previous
        // Event memory belongs to mpv and expires on the next wait_event call.
        for (ignored in 0 until 64) {
            val event = MpvEvent(api.mpv_wait_event(current, 0.0))
            when (event.eventId) {
                0 -> break
                8 -> { loaded = true; ended = false }
                7 -> {
                    val reason = event.data?.getInt(0)
                    if (reason == 4) throw PlaybackFailure("Playback failed. Check your connection and source access, then retry.")
                    if (reason == 0) ended = true
                }
            }
        }
        if (++polls % 8 == 1 || tracks.isEmpty()) {
            tracks = (0 until (property("track-list/count")?.toIntOrNull() ?: 0).coerceIn(0, 256)).mapNotNull { index ->
                val prefix = "track-list/$index"
                val type = property("$prefix/type") ?: return@mapNotNull null
                if (type != "audio" && type != "sub") return@mapNotNull null
                val id = property("$prefix/id") ?: return@mapNotNull null
                val title = property("$prefix/title")?.takeIf { it.isNotBlank() }
                val language = property("$prefix/lang")?.takeIf { it.isNotBlank() }
                val codec = property("$prefix/codec")?.takeIf { it.isNotBlank() }
                type to PlaybackTrack(id, listOfNotNull(title, language, codec).joinToString(" · ").ifBlank { "Track $id" }, false)
            }
        }
        val audioId = property("aid")
        val subtitleId = property("sid")
        return previous.copy(
            positionMs = milliseconds(property("time-pos")) ?: previous.positionMs,
            durationMs = milliseconds(property("duration")) ?: previous.durationMs,
            paused = property("pause") == "yes", loading = !loaded,
            buffering = property("paused-for-cache") == "yes" || property("seeking") == "yes",
            seekable = property("seekable") == "yes", ended = ended || property("eof-reached") == "yes",
            volume = property("volume")?.toDoubleOrNull()?.toInt()?.coerceIn(0, 100) ?: previous.volume,
            audio = tracks.filter { it.first == "audio" }.map { it.second.copy(selected = it.second.id == audioId) },
            subtitles = listOf(PlaybackTrack("no", "Off", subtitleId == "no")) + tracks.filter { it.first == "sub" }.map { it.second.copy(selected = it.second.id == subtitleId) },
        )
    }
    @Synchronized override fun close() { handle?.let { handle = null; api.mpv_terminate_destroy(it) } }
}

internal fun milliseconds(value: String?): Long? = value?.toDoubleOrNull()
    ?.takeIf { it.isFinite() && it >= 0 && it < Long.MAX_VALUE / 1000.0 }?.let { (it * 1000).toLong() }
