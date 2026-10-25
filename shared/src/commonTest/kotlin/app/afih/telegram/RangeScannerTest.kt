package app.afih.telegram

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RangeScannerTest {
    private val range = MessageRange("@movies", 1, 45, null)
    private fun message(id: Long, topic: Long? = null) = IndexedMessage(MessageKey("bot", "@movies", id), "$id.mkv", topic)

    @Test fun deletedIdsAndAnEntireEmptyBatchDoNotEndScan() = runTest {
        val requested = mutableListOf<List<Long>>()
        val commits = mutableListOf<ScanBatch>()
        val scanner = RangeScanner(MessageGateway { _, _, ids ->
            requested.add(ids)
            listOf(message(1), message(3), message(45)).filter { it.key.messageId in ids }
        }, ScanBatchStore { commits.add(it) })
        scanner.scan("bot", range)
        assertEquals(listOf(20L, 40L, 45L), commits.map { it.scannedThrough })
        assertEquals(emptyList(), commits[1].messages)
        assertEquals(listOf(1L, 3L, 45L), commits.flatMap { it.messages }.map { it.key.messageId })
        assertEquals((1L..45L).toList(), requested.flatten())
    }

    @Test fun failedRequestDoesNotCommitPastSuccessfulWorkAndCanResume() = runTest {
        val commits = mutableListOf<ScanBatch>()
        val scanner = RangeScanner(MessageGateway { _, _, ids ->
            if (ids.first() == 21L) error("network unavailable")
            emptyList()
        }, ScanBatchStore { commits.add(it) })
        assertFailsWith<IllegalStateException> { scanner.scan("bot", range) }
        assertEquals(listOf(20L), commits.map { it.scannedThrough })
        val resumed = mutableListOf<Long>()
        RangeScanner(MessageGateway { _, _, ids -> resumed.addAll(ids); emptyList() }, ScanBatchStore {})
            .scan("bot", range, commits.last().scannedThrough)
        assertEquals((21L..45L).toList(), resumed)
    }

    @Test fun failedCommitDoesNotStartNextBatch() = runTest {
        var requests = 0
        val scanner = RangeScanner(MessageGateway { _, _, _ -> requests++; emptyList() }, ScanBatchStore { error("disk full") })
        assertFailsWith<IllegalStateException> { scanner.scan("bot", range) }
        assertEquals(1, requests)
    }

    @Test fun topicFilteringStillAdvancesAcrossOtherMessages() = runTest {
        val commits = mutableListOf<ScanBatch>()
        RangeScanner(MessageGateway { _, _, _ -> listOf(message(1, 10), message(2, 11), message(1, 10)) }, ScanBatchStore { commits.add(it) })
            .scan("bot", range.copy(lastId = 2, topicId = 10))
        assertEquals(listOf(message(1, 10)), commits.single().messages)
        assertEquals(2L, commits.single().scannedThrough)
    }

    @Test fun unexpectedSourceCannotContaminateIndex() = runTest {
        var committed = false
        val scanner = RangeScanner(MessageGateway { _, _, _ -> listOf(message(1).copy(key = MessageKey("other", "@movies", 1))) }, ScanBatchStore { committed = true })
        assertFailsWith<IllegalArgumentException> { scanner.scan("bot", range) }
        assertEquals(false, committed)
    }

    @Test fun maximumMessageIdAndCompletedRangeDoNotOverflowOrFetchAgain() = runTest {
        var requests = 0
        val maximum = Int.MAX_VALUE.toLong()
        val scanner = RangeScanner(MessageGateway { _, _, _ -> requests++; emptyList() }, ScanBatchStore {})
        val last = range.copy(firstId = maximum, lastId = maximum)
        scanner.scan("bot", last)
        scanner.scan("bot", last, scannedThrough = maximum)
        assertEquals(1, requests)
    }
}
