package app.afih.desktop.playback

import com.sun.jna.*
import kotlin.test.*

class MpvEngineTest {
    private class FakeMpv : MpvApi {
        val options = mutableMapOf<String, String>()
        val properties = mutableMapOf<String, String>()
        val commands = mutableListOf<List<String>>()
        val events = ArrayDeque<Int>()
        val allocated = mutableMapOf<Pointer, Memory>()
        val event = Memory(24).apply { clear() }
        val end = Memory(8).apply { clear() }
        var destroyed = 0
        var initializeResult = 0
        override fun mpv_create() = Pointer(1)
        override fun mpv_set_option_string(handle: Pointer, name: String, value: String): Int { options[name] = value; return 0 }
        override fun mpv_initialize(handle: Pointer) = initializeResult
        override fun mpv_command(handle: Pointer, arguments: Pointer): Int {
            commands.add(buildList<String> {
                for (index in 0..100) {
                    val pointer = arguments.getPointer(index * Native.POINTER_SIZE.toLong()) ?: break
                    add(pointer.getString(0, "UTF-8"))
                }
            })
            return 0
        }
        override fun mpv_set_property_string(handle: Pointer, name: String, value: String): Int { properties[name] = value; return 0 }
        override fun mpv_get_property_string(handle: Pointer, name: String): Pointer? {
            val value = properties[name] ?: return null
            return Memory(value.toByteArray(Charsets.UTF_8).size + 1L).also { it.setString(0, value, "UTF-8"); allocated[it] = it }
        }
        override fun mpv_free(pointer: Pointer) { allocated.remove(pointer)?.close() }
        override fun mpv_wait_event(handle: Pointer, timeout: Double): Pointer {
            event.clear()
            val id = events.removeFirstOrNull() ?: 0
            event.setInt(0, id)
            if (id == 7) event.setPointer(16, end)
            return event
        }
        override fun mpv_terminate_destroy(handle: Pointer) { destroyed++ }
    }
    @Test fun embedsInUnsignedWindowsHandleAndDisablesExternalUiAndConfiguration() {
        val fake = FakeMpv()
        MpvEngine(-1, startMs = 12_500, api = fake).use {
            assertEquals("4294967295", fake.options["wid"])
            assertEquals("12.5", fake.options["start"])
            assertEquals("no", fake.options["osc"])
            assertEquals("no", fake.options["config"])
            assertEquals("no", fake.options["load-scripts"])
            assertEquals("gpu", fake.options["vo"])
        }
        assertEquals(1, fake.destroyed)
    }
    @Test fun commandsPreserveArgumentBoundariesAndUtf8AndCloseIsIdempotent() {
        val fake = FakeMpv()
        val engine = MpvEngine(100, api = fake)
        engine.command("loadfile", "http://127.0.0.1/a b/日本語", "replace")
        assertEquals(listOf("loadfile", "http://127.0.0.1/a b/日本語", "replace"), fake.commands.single())
        engine.close(); engine.close()
        engine.command("cycle", "pause")
        assertEquals(1, fake.commands.size)
        assertEquals(1, fake.destroyed)
    }
    @Test fun snapshotDecodesPlaybackTracksAndFreesEveryNativeString() {
        val fake = FakeMpv()
        fake.events += 8
        fake.properties += mapOf("time-pos" to "3.25", "duration" to "60", "pause" to "yes", "seekable" to "yes",
            "volume" to "65", "aid" to "2", "sid" to "no", "track-list/count" to "2",
            "track-list/0/type" to "audio", "track-list/0/id" to "2", "track-list/0/lang" to "eng", "track-list/0/codec" to "aac",
            "track-list/1/type" to "sub", "track-list/1/id" to "3", "track-list/1/title" to "English")
        MpvEngine(100, api = fake).use { engine ->
            val state = engine.snapshot(PlaybackUi())
            assertEquals(3250L, state.positionMs)
            assertEquals(60000L, state.durationMs)
            assertTrue(state.paused && state.seekable && !state.loading)
            assertTrue(state.audio.single().selected)
            assertEquals("eng · aac", state.audio.single().label)
            assertTrue(state.subtitles.first().selected)
            assertEquals("English", state.subtitles.last().label)
            assertTrue(fake.allocated.isEmpty())
            engine.setProperty("sid", "3")
            assertTrue(engine.snapshot(state).subtitles.last().selected)
        }
    }
    @Test fun initializationFailureDestroysHandleAndNativePlaybackErrorsAreSafe() {
        val failed = FakeMpv().apply { initializeResult = -1 }
        assertFailsWith<PlaybackFailure> { MpvEngine(100, api = failed) }
        assertEquals(1, failed.destroyed)
        val fake = FakeMpv().apply { events += 7; end.setInt(0, 4) }
        MpvEngine(100, api = fake).use { engine -> assertFailsWith<PlaybackFailure> { engine.snapshot(PlaybackUi()) } }
    }
    @Test fun invalidNativeTimesAreRejectedAndDisplayTimesAreFormatted() {
        listOf(null, "NaN", "Infinity", "-1", "99999999999999999999999").forEach { assertNull(milliseconds(it)) }
        assertEquals(500L, milliseconds("0.5"))
        assertEquals("01:05", playbackClock(65_000))
        assertEquals("1:01:01", playbackClock(3_661_000))
    }
}
