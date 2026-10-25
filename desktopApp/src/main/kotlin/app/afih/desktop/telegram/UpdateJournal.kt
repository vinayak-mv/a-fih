package app.afih.desktop.telegram

import app.afih.desktop.index.SyncStore
import app.afih.telegram.MessageKey
import kotlinx.serialization.json.*

/** Persist update work before acting on it. Cache eviction is never treated as a remote deletion. */
internal fun journalUpdate(store: SyncStore, provider: String, chats: Set<String>, update: JsonObject): Boolean {
    val type = update.string("@type")
    val message = when(type) {
        "updateNewMessage" -> update.obj("message")
        "updateChatLastMessage" -> update.obj("last_message")
        "updateNewChat" -> update.obj("chat")?.obj("last_message")
        else -> null
    }
    val chat = message?.number("chat_id")?.toString() ?: update.number("chat_id").toString()
    if (chat !in chats) return false
    fun raw(value: Long) = runCatching { fromTdMessageId(value) }.getOrNull()
    if (message != null) raw(message.number("id"))?.let { store.watermark(provider,chat,it) }
    if(type == "updateMessageContent") raw(update.number("message_id"))?.let { store.enqueue(MessageKey(provider,chat,it),"refresh") }
    if(type == "updateDeleteMessages" && update["from_cache"]?.jsonPrimitive?.booleanOrNull != true) {
        (update["message_ids"] as? JsonArray).orEmpty().forEach { id -> raw(id.jsonPrimitive.long)?.let {
            store.enqueue(MessageKey(provider,chat,it),if(update["is_permanent"]?.jsonPrimitive?.booleanOrNull == true) "deleted" else "inaccessible")
        } }
    }
    return message != null || type in setOf("updateMessageContent","updateDeleteMessages")
}
