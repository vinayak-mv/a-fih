package app.afih.desktop.metadata

import app.afih.metadata.*
import app.afih.model.*
import app.afih.telegram.*
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class MetadataServiceTest {
    private val configuration = AppConfiguration(tmdb = TmdbSettings("test-only-token"))
    private fun file(id: Long) = CatalogFile(IndexedMessage(MessageKey("bot", "chat", id), "A.Film.2021.1080p.mkv", title = "A Film 2021 1080p"), null, setOf(LibraryKind.MOVIES))
    private fun searchResult() = Json.parseToJsonElement("""{"results":[{"id":42,"title":"A Film","original_title":"A Film","release_date":"2021-01-01"}]}""").jsonObject
    private fun detailResult() = Json.parseToJsonElement("""{"id":42,"title":"A Film","release_date":"2021-01-01","poster_path":"/poster.jpg"}""").jsonObject

    @Test fun qualityVersionsShareRequestsAndStoredMetadataSurvivesRestart() = runTest {
        val directory = Files.createTempDirectory("afih-metadata")
        val database = directory.resolve("index.sqlite")
        try {
            var searches = 0
            var details = 0
            val store = MetadataStore(database)
            val service = MetadataService(store) { settings -> TmdbClient(TmdbTransport { path, _ ->
                if (path.startsWith("/search")) { searches++; searchResult() } else { details++; detailResult() }
            }, settings.language) }
            val alternate = file(2).let { it.copy(media = it.media.copy(fileName = "A.Film.2021.720p.mkv", title = "A Film 2021 720p")) }
            val files = listOf(file(1), alternate)
            service.enrich(configuration, files) { _, _ -> }
            service.enrich(configuration, files) { _, _ -> }
            assertEquals(1, searches)
            assertEquals(1, details)
            val reopened = MetadataStore(database).read(files.map { it.media.key }, "en-US")
            assertEquals(setOf(42L), reopened.values.map { it.details?.id }.toSet())
            assertEquals(2, reopened.size)
        } finally { Files.list(directory).use { it.forEach(Files::delete) }; Files.delete(directory) }
    }

    @Test fun transientFailuresAreNotPersistedAsNoMatchesAndUnmatchedCanRetry() = runTest {
        val directory = Files.createTempDirectory("afih-metadata-retry")
        try {
            val store = MetadataStore(directory.resolve("index.sqlite"))
            var fail = true
            var found = false
            val service = MetadataService(store) { settings -> TmdbClient(TmdbTransport { path, _ ->
                if (fail) throw MetadataFailure("Temporarily unavailable")
                if (!path.startsWith("/search")) detailResult()
                else if (found) searchResult() else buildJsonObject { putJsonArray("results") {} }
            }, settings.language) }
            val files = listOf(file(1))
            assertFailsWith<MetadataFailure> { service.enrich(configuration, files) { _, _ -> } }
            assertTrue(store.read(files.map { it.media.key }, "en-US").isEmpty())
            fail = false
            service.enrich(configuration, files) { _, _ -> }
            assertNull(store.read(listOf(file(1).media.key), "en-US").values.single().details)
            found = true
            service.enrich(configuration, files, retryUnmatched = true) { _, _ -> }
            assertEquals(42L, store.read(listOf(file(1).media.key), "en-US").values.single().details?.id)
        } finally { Files.list(directory).use { it.forEach(Files::delete) }; Files.delete(directory) }
    }

    @Test fun metadataTablesPreserveExistingTelegramFilesAndScanProgress() {
        val directory = Files.createTempDirectory("afih-metadata-migration")
        try {
            val path = directory.resolve("index.sqlite")
            val index = app.afih.desktop.index.MediaIndex(path)
            val canonical = TelegramSource("source", "bot", "Movies", "-1000000123456", 1, 20,
                libraryIds = setOf("movies"), startLink = "start", endLink = "end")
            val media = file(1).media.copy(key = MessageKey("bot", canonical.chat, 1))
            index.commit(canonical, "scan", ScanBatch(listOf(media), 20))
            val store = MetadataStore(path)
            store.save(media.key, "en-US", FileMetadata("match", TitleDetails(42, MediaType.MOVIE, "A Film"), null, null))
            assertEquals(20L, index.checkpoint(canonical, "scan"))
            assertEquals(media.fileName, index.list(setOf("source")).single().fileName)
            assertEquals(42L, store.read(listOf(media.key), "en-US").values.single().details?.id)
        } finally { Files.list(directory).use { it.forEach(Files::delete) }; Files.delete(directory) }
    }
}
