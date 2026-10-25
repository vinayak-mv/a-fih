package app.afih.desktop.telegram

import app.afih.desktop.index.*
import app.afih.model.TelegramSource
import app.afih.telegram.*
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class SyncRecoveryTest {
    private val chat = "-1000000000123"
    private val source = TelegramSource("source","bot","Example",chat,1,40,libraryIds=setOf("library"),startLink="t.me/c/123/1",endLink="t.me/c/123/40")
    private fun database(block: (Path) -> Unit) {
        val folder = Files.createTempDirectory("afih-sync-test")
        try { block(folder.resolve("media.sqlite")) }
        finally { Files.walk(folder).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }
    @Test fun receivedUpdatesJournalWatermarksEditsAndTrueDeletionsAcrossRestart() = database { path ->
        val store = SyncStore(path)
        journalUpdate(store,"bot",setOf(chat),buildJsonObject {
            put("@type","updateNewMessage"); putJsonObject("message") { put("chat_id",chat.toLong()); put("id",toTdMessageId(120)) }
        })
        journalUpdate(store,"bot",setOf(chat),buildJsonObject {
            put("@type","updateMessageContent"); put("chat_id",chat.toLong()); put("message_id",toTdMessageId(7))
        })
        fun deleted(cached: Boolean) = buildJsonObject {
            put("@type","updateDeleteMessages"); put("chat_id",chat.toLong()); put("from_cache",cached); put("is_permanent",true)
            putJsonArray("message_ids") { add(toTdMessageId(9)) }
        }
        journalUpdate(store,"bot",setOf(chat),deleted(true))
        assertEquals(listOf(7L,120L),store.pending("bot").map { it.key.messageId })
        journalUpdate(store,"bot",setOf(chat),deleted(false))
        val reopened = SyncStore(path)
        assertEquals(120L,reopened.latest("bot",chat))
        assertEquals(listOf("refresh","deleted","refresh"),reopened.pending("bot").map { it.kind })
        reopened.watermark("bot",chat,100)
        assertEquals(120L,reopened.latest("bot",chat))
        assertEquals(0L,reopened.latest("other",chat))
    }
    @Test fun newerEditCannotBeAcknowledgedByAnOlderInFlightRefresh() = database { path ->
        val store = SyncStore(path)
        val key = MessageKey("bot",chat,7)
        store.enqueue(key,"refresh")
        val old = store.pending("bot").single()
        store.enqueue(key,"deleted")
        store.acknowledge(old)
        assertEquals("deleted",store.pending("bot").single().kind)
        store.acknowledge(store.pending("bot").single())
        assertTrue(store.pending("bot").isEmpty())
    }
    @Test fun schedulesRespectDisabledModesRestartCooldownAndDurableRetry() = database { path ->
        val store = SyncStore(path)
        val manual = source.copy(scanOnLaunch=false,automaticUpdates=false,scanIntervalMinutes=0)
        assertFalse(syncDue(manual,SyncRun(),100000,0,false,100,40,true,0))
        assertTrue(syncDue(source,SyncRun(),100000,0,false,40,40,false,0))
        assertFalse(syncDue(source,SyncRun(),100000,0,false,120,40,true,200000))
        store.failure(source.id,200000)
        store.deferProvider("bot",250000)
        val reopened = SyncStore(path)
        assertFalse(syncDue(source,reopened.run(source.id),220000,0,true,120,40,true,reopened.cooldown("bot")))
        assertTrue(syncDue(source,reopened.run(source.id),260000,0,true,120,40,true,reopened.cooldown("bot")))
        reopened.success(source.id,260000)
        assertEquals(0,reopened.run(source.id).failures)
        assertFalse(syncDue(source,reopened.run(source.id),260001,0,true,40,40,false,0))
        assertTrue(syncDue(source,reopened.run(source.id),260000+15*60000,0,true,40,40,false,0))
    }
    @Test fun transientFailuresRetryWithBackoffButFloodWaitAndCancellationNeverSpin() = runTest {
        val waits = mutableListOf<Long>()
        var calls = 0
        val result = telegramRetry(wait={ waits.add(it) }) { if(++calls < 3) throw TelegramFailure("offline","NETWORK",true); "ok" }
        assertEquals("ok",result)
        assertEquals(listOf(1000L,2000L),waits)
        calls = 0
        assertFailsWith<TelegramFailure> { telegramRetry(wait={ error("must not retry") }) { calls++; throw TelegramFailure("wait","RATE_LIMIT",true,60000) } }
        assertEquals(1,calls)
        assertFailsWith<CancellationException> { telegramRetry(wait={ error("must not retry") }) { throw CancellationException("paused") } }
    }
    @Test fun floodWaitParsingKeepsReasonsSafeAndDistinguishesMissingAndAccessErrors() {
        val flood = telegramFailure(buildJsonObject { put("code",429); put("message","Too Many Requests: retry after 123") })
        assertEquals(123000L,flood.retryAfterMs)
        assertEquals("RATE_LIMIT",flood.kind)
        assertEquals("MISSING",telegramFailure(buildJsonObject { put("code",404); put("message","secret details") }).kind)
        assertEquals("ACCESS",telegramFailure(buildJsonObject { put("code",403); put("message","secret details") }).kind)
        assertFalse(flood.message.orEmpty().contains("123"))
    }
    @Test fun restartCatchupUsesDurableWatermarkAndCrossesEmptyBatches() = runBlocking {
        val folder = Files.createTempDirectory("afih-catchup")
        try {
            val path = folder.resolve("media.sqlite")
            SyncStore(path).watermark("bot",chat,81)
            val index = MediaIndex(path)
            index.commit(source,"stable",ScanBatch(emptyList(),40))
            val target = source.copy(lastId=SyncStore(path).latest("bot",chat))
            val requests = mutableListOf<List<Long>>()
            RangeScanner(MessageGateway { _,_,ids -> requests.add(ids); if(81L in ids) listOf(IndexedMessage(MessageKey("bot",chat,81),"Found.mkv")) else emptyList() },
                ScanBatchStore { index.commit(target,"stable",it) }).scan("bot",MessageRange(chat,1,target.lastId,null),index.checkpoint(target,"stable"))
            assertEquals(listOf(41L,61L,81L),requests.map { it.first() })
            assertEquals(81L,MediaIndex(path).checkpoint(target,"stable"))
            assertEquals("Found.mkv",index.list(setOf(source.id)).single().fileName)
        } finally { Files.walk(folder).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }
}
