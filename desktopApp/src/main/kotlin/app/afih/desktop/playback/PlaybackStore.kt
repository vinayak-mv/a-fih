package app.afih.desktop.playback

import app.afih.telegram.MessageKey
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

internal class PlaybackStore(private val path: Path) {
    init {
        Class.forName("org.sqlite.JDBC")
        Files.createDirectories(path.toAbsolutePath().parent)
        connection().use { db -> db.createStatement().use {
            it.execute("""CREATE TABLE IF NOT EXISTS playback_progress (
                provider TEXT NOT NULL, chat TEXT NOT NULL, message_id INTEGER NOT NULL,
                position_ms INTEGER NOT NULL, duration_ms INTEGER NOT NULL,
                PRIMARY KEY(provider, chat, message_id))""")
        } }
    }
    private fun connection() = DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").also { db ->
        db.createStatement().use { it.execute("PRAGMA busy_timeout = 10000") }
    }
    fun position(key: MessageKey): Long = connection().use { db ->
        db.prepareStatement("SELECT position_ms, duration_ms FROM playback_progress WHERE provider=? AND chat=? AND message_id=?").use {
            it.setString(1, key.providerId); it.setString(2, key.chat); it.setLong(3, key.messageId)
            it.executeQuery().use { rows ->
                if (!rows.next()) 0L else resumePosition(rows.getLong(1), rows.getLong(2))
            }
        }
    }
    fun save(key: MessageKey, position: Long, duration: Long) {
        if (duration <= 0 || position < 0) return
        connection().use { db -> db.prepareStatement("""INSERT INTO playback_progress VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(provider, chat, message_id) DO UPDATE SET position_ms=excluded.position_ms, duration_ms=excluded.duration_ms""").use {
            it.setString(1, key.providerId); it.setString(2, key.chat); it.setLong(3, key.messageId)
            it.setLong(4, position.coerceAtMost(duration)); it.setLong(5, duration); it.executeUpdate()
        } }
    }
}

internal fun resumePosition(position: Long, duration: Long): Long =
    if (position >= 5_000 && duration > 0 && position < duration * .95) position else 0
