package app.afih.desktop.index

import app.afih.model.TelegramSource
import app.afih.telegram.*
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.*

class MediaIndexTest {
    private val source = TelegramSource("source", "bot", "Movies", "-1000000123456", 1, 100, libraryIds = setOf("library"), startLink = "t.me/c/123456/1", endLink = "t.me/c/123456/100")
    private fun media(id: Long) = IndexedMessage(MessageKey("bot", source.chat, id), "$id.mkv")

    private fun withIndex(block: (MediaIndex, Path) -> Unit) {
        val directory = Files.createTempDirectory("afih-index-test")
        try { block(MediaIndex(directory.resolve("index.sqlite")), directory.resolve("index.sqlite")) }
        finally { Files.list(directory).use { files -> files.forEach { Files.delete(it) } }; Files.delete(directory) }
    }

    @Test fun batchesAreDurableAndRepeatImportsDeduplicateAcrossSources() = withIndex { index, path ->
        index.commit(source, "signature", ScanBatch(listOf(media(1), media(3)), 20))
        index.commit(source, "signature", ScanBatch(listOf(media(3)), 40))
        val second = source.copy(id = "second")
        index.commit(second, "signature", ScanBatch(listOf(media(3)), 20))
        assertEquals(2, index.list(setOf(source.id, second.id)).size)
        assertEquals(40L, index.checkpoint(source, "signature"))
        assertEquals(40L, MediaIndex(path).checkpoint(source, "signature"))
        assertEquals(0L, index.checkpoint(source, "changed-range"))
        index.removeSource(source.id)
        assertEquals(listOf(3L), index.list(setOf(second.id)).map { it.key.messageId })
    }

    @Test fun failedTransactionRollsBackMediaAndCheckpointTogether() = withIndex { index, path ->
        val valid = media(1)
        // A conflicting duplicate key is an upsert, not a failure. Force a database
        // abort with a trigger, then verify no media/checkpoint escaped the transaction.
        index.commit(source, "before", ScanBatch(listOf(valid), 1))
        DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { db ->
            db.createStatement().use { it.execute("CREATE TRIGGER fail_checkpoint BEFORE UPDATE ON scan_progress WHEN NEW.signature = 'after' BEGIN SELECT RAISE(ABORT, 'test failure'); END") }
        }
        assertFails {
            index.commit(source, "after", ScanBatch(listOf(media(2)), 20))
        }
        assertEquals(1L, index.checkpoint(source, "before"))
        assertEquals(listOf(1L), index.list(setOf(source.id)).map { it.key.messageId })
    }
}
