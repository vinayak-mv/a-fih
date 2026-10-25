package app.afih.telegram

/** Raw MTProto IDs; a transport such as TDLib must translate its own ID encoding. */
data class MessageLink(val chat: String, val messageId: Long, val topicId: Long? = null)

data class MessageRange(val chat: String, val firstId: Long, val lastId: Long, val topicId: Long?) {
    companion object {
        fun fromLinks(start: String, end: String): MessageRange {
            val first = parseMessageLink(start)
            val last = parseMessageLink(end)
            require(first.chat == last.chat) { "Both links must belong to the same chat." }
            require(first.topicId == last.topicId) { "Both links must use the same topic scope." }
            require(first.messageId <= last.messageId) { "The end must not precede the start." }
            return MessageRange(first.chat, first.messageId, last.messageId, first.topicId)
        }
    }
}

fun parseMessageLink(value: String): MessageLink {
    val match = Regex("^(?:https?://)?(?:www\\.)?t\\.me/([^?#]+)(?:\\?([^#]*))?$", RegexOption.IGNORE_CASE)
        .matchEntire(value.trim()) ?: throw IllegalArgumentException("Enter a Telegram message link from t.me.")
    val path = match.groupValues[1].trimEnd('/').split('/')
    require(path.none { it.isBlank() }) { "Invalid message path." }
    val privateChat = path.first() == "c"
    val prefixSize = if (privateChat) 2 else 1
    require(path.size == prefixSize + 1 || path.size == prefixSize + 2) { "Link must identify a message, optionally in a topic." }
    val chat = if (privateChat) {
        val internal = path[1]
        require(internal.matches(Regex("[1-9][0-9]*"))) { "Invalid private channel ID." }
        val id = internal.toLongOrNull()
        require(id != null && id <= Long.MAX_VALUE - 1_000_000_000_000L) { "Private channel ID is out of range." }
        (-1_000_000_000_000L - id).toString()
    } else {
        require(path[0].matches(Regex("[A-Za-z][A-Za-z0-9_]{3,31}"))) { "Invalid channel username." }
        "@${path[0].lowercase()}"
    }
    fun positiveId(raw: String): Long {
        val id = raw.toLongOrNull()
        require(id != null && id in 1..Int.MAX_VALUE.toLong()) { "Message/topic ID is out of range." }
        return id
    }
    val pathTopic = if (path.size == prefixSize + 2) positiveId(path[prefixSize]) else null
    val queryTopics = match.groupValues[2].split('&').filter { it.startsWith("thread=") || it.startsWith("topic=") }
        .map { positiveId(it.substringAfter('=')) }
    require(queryTopics.distinct().size <= 1) { "Conflicting topic parameters." }
    val queryTopic = queryTopics.firstOrNull()
    require(pathTopic == null || queryTopic == null || pathTopic == queryTopic) { "Conflicting topic IDs." }
    return MessageLink(chat, positiveId(path.last()), pathTopic ?: queryTopic)
}
