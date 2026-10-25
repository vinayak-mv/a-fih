package app.afih.desktop.telegram

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

/** Injectable request boundary makes cold-start resolution testable without a live bot. */
internal suspend fun resolveTelegramChat(
    reference: String,
    call: suspend (JsonObject) -> JsonObject,
): Pair<String, String> {
    val chat = if (reference.startsWith('@')) {
        call(request("searchPublicChat") { put("username", reference.removePrefix("@")) })
    } else {
        val chatId = reference.toLong()
        require(chatId < -1_000_000_000_000L) { "Use a channel or supergroup message link." }
        val supergroupId = -(chatId + 1_000_000_000_000L)
        // createSupergroupChat needs a KNOWN supergroup, even with force=true.
        // getSupergroup hydrates it from storage or, for bots, a server lookup.
        // See the pinned TDLib ChatManager::get_channel and get_input_channel.
        call(request("getSupergroup") { put("supergroup_id", supergroupId) })
        call(request("createSupergroupChat") { put("supergroup_id", supergroupId); put("force", false) }).also {
            require(it.number("id") == chatId) { "Telegram resolved a different chat. Check the source link." }
        }
    }
    val type = requireNotNull(chat.obj("type")) { "Telegram did not return a chat type." }
    require(type.string("@type") == "chatTypeSupergroup") { "Use a channel or supergroup, not a direct bot conversation." }
    val supergroupId = type.number("supergroup_id")
    // Hydrating metadata is not proof of access; keep the actual permission check.
    call(request("getSupergroupFullInfo") { put("supergroup_id", supergroupId) })
    return chat.number("id").toString() to chat.string("title")
}
