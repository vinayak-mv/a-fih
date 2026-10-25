package app.afih.desktop.telegram

import app.afih.model.ParsingMode
import kotlin.test.*
import kotlinx.serialization.json.*

class TelegramCodecTest {
    @Test fun messageIdsRoundTripWithoutConfusingRawAndTdlibIds() {
        for (id in listOf(1L, 50L, Int.MAX_VALUE.toLong())) assertEquals(id, fromTdMessageId(toTdMessageId(id)))
        assertEquals(1_048_576L, toTdMessageId(1))
        assertFailsWith<IllegalArgumentException> { fromTdMessageId(toTdMessageId(1) + 1) }
        assertFailsWith<IllegalArgumentException> { toTdMessageId(0) }
    }

    private fun message() = buildJsonObject {
        put("id", toTdMessageId(35)); put("chat_id", -1000000123456L)
        putJsonObject("topic_id") { put("@type", "messageTopicForum"); put("forum_topic_id", 20) }
        putJsonObject("content") {
            put("@type", "messageDocument")
            putJsonObject("caption") { put("text", "A caption title\nMore details") }
            putJsonObject("document") {
                put("file_name", "A.Movie.2025.mkv"); put("mime_type", "application/octet-stream")
                putJsonObject("document") {
                    put("size", 5_000_000_000L)
                    putJsonObject("remote") { put("id", "remote-file-reference") }
                }
            }
        }
    }

    @Test fun mkvDocumentsTopicsLargeSizesAndCaptionPreferencesAreDecoded() {
        val media = assertNotNull(decodeMedia("bot", "-1000000123456", message(), ParsingMode.CAPTION))
        assertEquals(35L, media.key.messageId)
        assertEquals(20L, media.topicId)
        assertEquals(5_000_000_000L, media.sizeBytes)
        assertEquals("A caption title", media.title)
        assertEquals("A Movie 2025", decodeMedia("bot", "-1000000123456", message(), ParsingMode.FILE_NAME)?.title)
    }

    @Test fun nonMediaAndWrongChatsAreRejected() {
        assertNull(decodeMedia("bot", "-1000000123456", buildJsonObject {
            put("chat_id", -1000000123456L); putJsonObject("content") { put("@type", "messageText") }
        }, ParsingMode.BOTH))
        assertFailsWith<IllegalArgumentException> { decodeMedia("bot", "-1000000999999", message(), ParsingMode.BOTH) }
    }

    @Test fun serverErrorsNeverEchoRequestSecrets() {
        val secret = "987654321:sensitive"
        val failure = telegramFailure(buildJsonObject { put("code", 400); put("message", "Rejected request containing $secret") })
        assertFalse(failure.message.orEmpty().contains(secret))
        assertEquals("Telegram could not complete this request.", telegramFailure(buildJsonObject { put("code", 406); put("message", secret) }).message)
    }
}
