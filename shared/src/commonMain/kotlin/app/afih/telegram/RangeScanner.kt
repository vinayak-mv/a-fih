package app.afih.telegram

data class MessageKey(val providerId: String, val chat: String, val messageId: Long)

data class IndexedMessage(
    val key: MessageKey,
    val fileName: String,
    val topicId: Long? = null,
    val caption: String = "",
    val mimeType: String = "",
    val sizeBytes: Long = 0,
    val remoteFileId: String = "",
    val title: String = fileName,
)

fun interface MessageGateway {
    /** Successful missing/deleted/non-media IDs may be omitted. Failures must throw. */
    suspend fun fetch(providerId: String, chat: String, messageIds: List<Long>): List<IndexedMessage>
}

data class ScanBatch(val messages: List<IndexedMessage>, val scannedThrough: Long)

fun interface ScanBatchStore {
    /** Must atomically upsert media and persist the contiguous checkpoint. */
    suspend fun commit(batch: ScanBatch)
}

/** Bounded scans never infer an end from missing IDs or empty batches. */
class RangeScanner(private val gateway: MessageGateway, private val store: ScanBatchStore) {
    suspend fun scan(
        providerId: String,
        range: MessageRange,
        scannedThrough: Long = range.firstId - 1,
        batchSize: Int = 20,
    ) {
        require(range.firstId in 1..Int.MAX_VALUE.toLong() && range.lastId in range.firstId..Int.MAX_VALUE.toLong())
        require(scannedThrough in (range.firstId - 1)..range.lastId) { "Checkpoint is outside this range." }
        require(batchSize in 1..100) { "Batch size must be between 1 and 100." }
        var next = scannedThrough + 1
        while (next <= range.lastId) {
            val end = minOf(next + batchSize - 1, range.lastId)
            val ids = (next..end).toList()
            val messages = gateway.fetch(providerId, range.chat, ids)
            require(messages.all { it.key.providerId == providerId && it.key.chat == range.chat && it.key.messageId in next..end }) {
                "Transport returned a message outside the requested source/range."
            }
            val media = messages.filter { range.topicId == null || it.topicId == range.topicId }.distinctBy { it.key }
            store.commit(ScanBatch(media, end))
            next = end + 1
        }
    }
}
