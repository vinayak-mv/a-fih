package app.afih.desktop.index

import app.afih.desktop.playback.PlaybackStore
import app.afih.telegram.*
import java.nio.file.Path
import java.sql.DriverManager

data class PersonalState(val watched: Boolean = false, val favorite: Boolean = false, val watchlist: Boolean = false,
    val hidden: Boolean = false, val positionMs: Long = 0, val durationMs: Long = 0, val playedAt: Long = 0) {
    val inProgress get() = !watched && positionMs >= 5000 && durationMs > 0 && positionMs < durationMs * .95
}
data class MediaIssue(val key: MessageKey, val fileName: String, val kind: String, val message: String, val updatedAt: Long, val attempts: Int)

/** Personal flags and errors survive reindexing; hiding is a local tombstone, never a Telegram delete. */
class LibraryStateStore(private val path: Path) {
    init {
        PlaybackStore(path)
        db().use { connection -> connection.createStatement().use {
            it.execute("""CREATE TABLE IF NOT EXISTS media_issues (provider TEXT, chat TEXT, message_id INTEGER,
                file_name TEXT NOT NULL, kind TEXT NOT NULL, message TEXT NOT NULL, updated_at INTEGER NOT NULL,
                attempts INTEGER NOT NULL, PRIMARY KEY(provider,chat,message_id))""")
        } }
    }
    private fun db() = DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").also { connection ->
        connection.createStatement().use { it.execute("PRAGMA busy_timeout=10000") }
    }
    fun states(): Map<MessageKey, PersonalState> = db().use { connection -> connection.createStatement().use { statement ->
        val result = mutableMapOf<MessageKey, PersonalState>()
        statement.executeQuery("SELECT * FROM personal_media").use { rows -> while (rows.next()) {
            result[MessageKey(rows.getString("provider"), rows.getString("chat"), rows.getLong("message_id"))] = PersonalState(rows.getBoolean("watched"), rows.getBoolean("favorite"), rows.getBoolean("watchlist"), rows.getBoolean("hidden"))
        } }
        statement.executeQuery("SELECT * FROM playback_progress").use { rows -> while (rows.next()) {
            val key = MessageKey(rows.getString("provider"), rows.getString("chat"), rows.getLong("message_id"))
            result[key] = (result[key] ?: PersonalState()).copy(positionMs = rows.getLong("position_ms"), durationMs = rows.getLong("duration_ms"), playedAt = rows.getLong("updated_at"))
        } }
        result
    } }
    fun set(keys: List<MessageKey>, field: String, value: Boolean) {
        require(field in setOf("watched", "favorite", "watchlist", "hidden"))
        db().use { connection ->
            connection.autoCommit = false
            try {
                keys.forEach { key ->
                    connection.prepareStatement("INSERT INTO personal_media(provider,chat,message_id,$field) VALUES(?,?,?,?) ON CONFLICT(provider,chat,message_id) DO UPDATE SET $field=excluded.$field").use {
                        it.setString(1,key.providerId); it.setString(2,key.chat); it.setLong(3,key.messageId); it.setBoolean(4,value); it.executeUpdate()
                    }
                    if (field == "watched") connection.prepareStatement("DELETE FROM playback_progress WHERE provider=? AND chat=? AND message_id=?").use {
                        it.setString(1,key.providerId); it.setString(2,key.chat); it.setLong(3,key.messageId); it.executeUpdate()
                    }
                }
                connection.commit()
            } catch (failure: Exception) { connection.rollback(); throw failure }
        }
    }
    fun issue(media: IndexedMessage, kind: String, message: String) = db().use { connection ->
        val hidden = connection.prepareStatement("SELECT hidden FROM personal_media WHERE provider=? AND chat=? AND message_id=?").use {
            it.setString(1,media.key.providerId); it.setString(2,media.key.chat); it.setLong(3,media.key.messageId)
            it.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
        }
        if(hidden) return@use 0
        connection.prepareStatement("""INSERT INTO media_issues VALUES(?,?,?,?,?,?,?,1) ON CONFLICT(provider,chat,message_id)
            DO UPDATE SET file_name=excluded.file_name,kind=excluded.kind,message=excluded.message,updated_at=excluded.updated_at,attempts=media_issues.attempts+1""").use {
            it.setString(1,media.key.providerId); it.setString(2,media.key.chat); it.setLong(3,media.key.messageId)
            it.setString(4,media.fileName); it.setString(5,kind); it.setString(6,message); it.setLong(7,System.currentTimeMillis()); it.executeUpdate()
        }
    }
    fun clearIssue(key: MessageKey) = db().use { connection ->
        connection.prepareStatement("DELETE FROM media_issues WHERE provider=? AND chat=? AND message_id=?").use {
            it.setString(1,key.providerId); it.setString(2,key.chat); it.setLong(3,key.messageId); it.executeUpdate()
        }
    }
    fun issues(): List<MediaIssue> = db().use { connection -> connection.createStatement().use { statement ->
        statement.executeQuery("SELECT * FROM media_issues ORDER BY updated_at DESC").use { rows -> buildList {
            while (rows.next()) add(MediaIssue(MessageKey(rows.getString("provider"),rows.getString("chat"),rows.getLong("message_id")),rows.getString("file_name"),rows.getString("kind"),rows.getString("message"),rows.getLong("updated_at"),rows.getInt("attempts")))
        } }
    } }
}
