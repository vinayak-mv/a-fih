package app.afih.desktop.telegram

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class ChatResolverTest {
    private val chatId = -1000000123456L
    private val supergroupId = 123456L
    private fun chat() = buildJsonObject {
        put("@type", "chat"); put("id", chatId); put("title", "My media")
        putJsonObject("type") { put("@type", "chatTypeSupergroup"); put("supergroup_id", supergroupId) }
    }

    @Test fun coldPrivateChatIsLoadedBeforeCreationWithoutAnyIncomingMessage() = runTest {
        // Run with two independent, initially empty fake sessions to cover restart.
        repeat(2) {
            var known = false
            val methods = mutableListOf<String>()
            val result = resolveTelegramChat(chatId.toString()) { query ->
                methods += query.string("@type")
                assertEquals(supergroupId, query.number("supergroup_id"))
                when (query.string("@type")) {
                    "getSupergroup" -> { known = true; request("supergroup") }
                    "createSupergroupChat" -> {
                        check(known) { "Supergroup not found" }
                        assertEquals(false, query["force"]?.jsonPrimitive?.boolean)
                        chat()
                    }
                    "getSupergroupFullInfo" -> request("supergroupFullInfo")
                    else -> error("Unexpected request")
                }
            }
            assertEquals(chatId.toString() to "My media", result)
            assertEquals(listOf("getSupergroup", "createSupergroupChat", "getSupergroupFullInfo"), methods)
        }
    }

    @Test fun publicChatIsResolvedThroughItsUsername() = runTest {
        val methods = mutableListOf<String>()
        val result = resolveTelegramChat("@my_media") { query ->
            methods += query.string("@type")
            when (query.string("@type")) {
                "searchPublicChat" -> { assertEquals("my_media", query.string("username")); chat() }
                "getSupergroupFullInfo" -> request("supergroupFullInfo")
                else -> error("Unexpected request")
            }
        }
        assertEquals(chatId.toString(), result.first)
        assertEquals(listOf("searchPublicChat", "getSupergroupFullInfo"), methods)
    }

    @Test fun actualAccessFailuresAreNotHiddenBySuccessfulMetadataLoading() = runTest {
        val denial = TelegramFailure("Telegram denied access to this source.")
        val failure = assertFailsWith<TelegramFailure> {
            resolveTelegramChat(chatId.toString()) { query -> when (query.string("@type")) {
                "getSupergroup" -> request("supergroup")
                "createSupergroupChat" -> chat()
                else -> throw denial
            } }
        }
        assertSame(denial, failure)
    }

    @Test fun failedHydrationStopsResolutionAndWrongChatCannotBeAccepted() = runTest {
        var calls = 0
        assertFailsWith<TelegramFailure> {
            resolveTelegramChat(chatId.toString()) { calls++; throw TelegramFailure("Chat lookup failed.") }
        }
        assertEquals(1, calls)
        assertFailsWith<IllegalArgumentException> {
            resolveTelegramChat(chatId.toString()) { query ->
                if (query.string("@type") == "getSupergroup") request("supergroup")
                else JsonObject(chat() + ("id" to JsonPrimitive(chatId - 1)))
            }
        }
    }
}
