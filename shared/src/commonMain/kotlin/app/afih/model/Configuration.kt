package app.afih.model

import kotlinx.serialization.Serializable

@Serializable
enum class ParsingMode { FILE_NAME, CAPTION, BOTH }

@Serializable
enum class LibraryKind { MOVIES, SERIES, ANIME, MUSIC, FILES }

@Serializable
data class TelegramCredentials(val apiId: Int, val apiHash: String, val botToken: String) {
    init {
        require(apiId > 0) { "API ID must be a positive number." }
        require(apiHash.matches(Regex("[0-9a-fA-F]{32}"))) { "API hash must contain 32 hexadecimal characters." }
        require(botToken.matches(Regex("[0-9]+:[A-Za-z0-9_-]+"))) { "Enter a valid bot token." }
    }

    override fun toString() = "TelegramCredentials([redacted])"
}

@Serializable
data class TelegramProvider(
    val id: String,
    val name: String,
    val credentials: TelegramCredentials,
    val parsingMode: ParsingMode = ParsingMode.BOTH,
) {
    init { require(name.isNotBlank()) { "Provider name is required." } }
}

@Serializable
data class MediaLibrary(val id: String, val name: String, val kind: LibraryKind) {
    init { require(name.isNotBlank()) { "Library name is required." } }
}

@Serializable
data class AppConfiguration(
    val schemaVersion: Int = 1,
    val providers: List<TelegramProvider> = emptyList(),
    val libraries: List<MediaLibrary> = emptyList(),
    val sources: List<TelegramSource> = emptyList(),
)

@Serializable
data class TelegramSource(
    val id: String,
    val providerId: String,
    val name: String,
    /** Canonical numeric chat ID returned by the transport, never a mutable username. */
    val chat: String,
    val firstId: Long,
    val lastId: Long,
    val topicId: Long? = null,
    val parsingMode: ParsingMode? = null,
    val libraryIds: Set<String>,
    val startLink: String,
    val endLink: String,
) {
    init {
        require(name.isNotBlank()) { "Source name is required." }
        require(chat.toLongOrNull() != null) { "Source requires a resolved chat." }
        require(firstId in 1..Int.MAX_VALUE.toLong() && lastId in firstId..Int.MAX_VALUE.toLong()) { "Invalid message range." }
        require(topicId == null || topicId in 1..Int.MAX_VALUE.toLong()) { "Invalid topic ID." }
        require(libraryIds.isNotEmpty()) { "Select at least one library." }
    }

    val scanSignature: String get() = "$providerId|$chat|$firstId|$lastId|$topicId|$parsingMode"
}
