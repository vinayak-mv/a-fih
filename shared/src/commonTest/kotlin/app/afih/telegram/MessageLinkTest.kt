package app.afih.telegram

import app.afih.model.TelegramCredentials
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class MessageLinkTest {
    @Test fun publicAndPrivateLinks() {
        assertEquals(MessageLink("@movies", 123), parseMessageLink("https://t.me/Movies/123"))
        assertEquals(MessageLink("-1001234567890", 456), parseMessageLink("https://t.me/c/1234567890/456"))
    }

    @Test fun topicsAndQueryParameters() {
        assertEquals(MessageLink("-1000000123456", 456, 20), parseMessageLink("https://t.me/c/123456/20/456?single"))
        assertEquals(MessageLink("@movies", 456, 20), parseMessageLink("t.me/movies/456?thread=20"))
        assertEquals(MessageLink("@movies", 456, 20), parseMessageLink("t.me/movies/20/456"))
    }

    @Test fun rejectMixedChatsTopicsReversedRangesAndInvalidLinks() {
        listOf(
            "https://evil.example/movies/1", "https://t.me/movies/0", "https://t.me/+invite",
            "https://t.me/c/1/2147483648", "https://t.me/movies/10/12?thread=11",
            "https://t.me/c/999999999999999999999999/1",
        ).forEach { assertFailsWith<IllegalArgumentException> { parseMessageLink(it) } }
        assertFailsWith<IllegalArgumentException> { MessageRange.fromLinks("t.me/movies/1", "t.me/series/2") }
        assertFailsWith<IllegalArgumentException> { MessageRange.fromLinks("t.me/movies/2", "t.me/movies/1") }
        assertFailsWith<IllegalArgumentException> { MessageRange.fromLinks("t.me/movies/10/12", "t.me/movies/11/14") }
    }

    @Test fun credentialsNeverExposeSecretsInDiagnostics() {
        val credentials = TelegramCredentials(123, "a".repeat(32), "123:secret")
        assertFalse(credentials.toString().contains("secret"))
        assertFalse(credentials.toString().contains(credentials.apiHash))
    }
}
