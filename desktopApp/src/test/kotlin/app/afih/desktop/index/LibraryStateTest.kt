package app.afih.desktop.index

import app.afih.desktop.*
import app.afih.desktop.metadata.*
import app.afih.desktop.playback.PlaybackStore
import app.afih.metadata.*
import app.afih.model.TelegramSource
import app.afih.telegram.*
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.*

class LibraryStateTest {
    private val key = MessageKey("bot","-1000000000123",7)
    private val media = IndexedMessage(key,"Example.mkv")
    private fun database(block: (Path) -> Unit) {
        val folder = Files.createTempDirectory("afih-library-state")
        try { block(folder.resolve("media.sqlite")) }
        finally { Files.walk(folder).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }
    @Test fun personalFlagsAndIssuesPersistAndHiddenItemsDoNotReappear() = database { path ->
        val store = LibraryStateStore(path)
        store.set(listOf(key),"favorite",true)
        store.set(listOf(key),"watchlist",true)
        store.issue(media,"NETWORK","Connection failed")
        store.issue(media,"MISSING","Message missing")
        val reopened = LibraryStateStore(path)
        assertTrue(reopened.states().getValue(key).favorite)
        assertTrue(reopened.states().getValue(key).watchlist)
        assertEquals(2,reopened.issues().single().attempts)
        assertEquals("MISSING",reopened.issues().single().kind)
        reopened.set(listOf(key),"hidden",true)
        reopened.clearIssue(key)
        reopened.issue(media,"MISSING","Message missing")
        assertTrue(reopened.issues().isEmpty())
        assertTrue(reopened.states().getValue(key).hidden)
    }
    @Test fun oldProgressSchemaMigratesWithoutLosingResumeAndCompletionMarksWatched() = database { path ->
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:$path").use { db -> db.createStatement().use {
            it.execute("CREATE TABLE playback_progress(provider TEXT,chat TEXT,message_id INTEGER,position_ms INTEGER,duration_ms INTEGER,PRIMARY KEY(provider,chat,message_id))")
            it.execute("INSERT INTO playback_progress VALUES('bot','${key.chat}',7,40000,100000)")
        } }
        val store = LibraryStateStore(path)
        assertTrue(store.states().getValue(key).inProgress)
        assertEquals(40000L,PlaybackStore(path).position(key))
        PlaybackStore(path).save(key,97000,100000)
        assertTrue(store.states().getValue(key).watched)
        assertFalse(store.states().getValue(key).inProgress)
        store.set(listOf(key),"watched",false)
        assertEquals(0L,PlaybackStore(path).position(key))
        assertFalse(store.states().getValue(key).watched)
    }
    @Test fun qualitiesShareWatchStateWhileDifferentEpisodesRemainIndependent() {
        fun file(id: Long,episode: Int,watched: Boolean,position: Long = 0) = CatalogFile(media.copy(key=key.copy(messageId=id)),
            FileMetadata("match",TitleDetails(1,MediaType.TV,"Example"),1,episode),personal=PersonalState(watched=watched,positionMs=position,durationMs=100000,playedAt=id))
        val first = file(1,1,true)
        val alternate = file(2,1,false,40000)
        val second = file(3,2,false,30000)
        assertTrue(titleWatched(listOf(first,alternate)))
        assertFalse(titleWatched(listOf(first,alternate,second)))
        assertEquals(listOf(second),continuingFiles(listOf(first,alternate,second)))
        assertFalse(collectionMatches(listOf(first,alternate),CollectionFilter.IN_PROGRESS))
        assertTrue(collectionMatches(listOf(second),CollectionFilter.UNWATCHED))
    }
    @Test fun editRefreshDoesNotRegressScanCheckpointAndLegacyCheckpointsRemainReadable() = database { path ->
        val source = TelegramSource("source","bot","Example",key.chat,1,100,libraryIds=setOf("library"),startLink="t.me/c/123/1",endLink="t.me/c/123/100")
        val index = MediaIndex(path)
        index.commit(source,"legacy",ScanBatch(listOf(media),100))
        assertEquals(100L,index.checkpoint(source,"new","legacy"))
        index.commit(source,"new",ScanBatch(emptyList(),100))
        index.commit(source,"new",ScanBatch(listOf(media.copy(fileName="Edited.mkv")),100),advanceProgress=false)
        assertEquals(100L,index.checkpoint(source,"new"))
        assertEquals("Edited.mkv",index.get(key)?.fileName)
        val extended = source.copy(lastId=140)
        index.commit(extended,"new",ScanBatch(emptyList(),120))
        assertEquals(120L,MediaIndex(path).checkpoint(extended,"new"))
    }
}
