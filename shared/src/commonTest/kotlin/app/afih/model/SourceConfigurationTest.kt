package app.afih.model

import kotlinx.serialization.json.Json
import kotlin.test.*

class SourceConfigurationTest {
    @Test fun initialPreviewSettingsRemainReadable() {
        val previous = """{"schemaVersion":1,"providers":[],"libraries":[{"id":"movies","name":"Movies","kind":"MOVIES"}]}"""
        val loaded = Json.decodeFromString<AppConfiguration>(previous)
        assertTrue(loaded.sources.isEmpty())
        assertEquals("Movies", loaded.libraries.single().name)
    }

    @Test fun sourceSupportsMultipleLibrariesAndRejectsAnUnboundedRange() {
        val source = TelegramSource("source", "bot", "Movies", "-1000000123456", 1, 40,
            libraryIds = setOf("movies", "favorites"), startLink = "t.me/c/123456/1", endLink = "t.me/c/123456/40")
        assertEquals(2, source.libraryIds.size)
        assertFailsWith<IllegalArgumentException> { source.copy(lastId = 0) }
        assertFailsWith<IllegalArgumentException> { source.copy(libraryIds = emptySet()) }
        assertNotEquals(source.scanSignature, source.copy(topicId = 20).scanSignature)
    }
}
