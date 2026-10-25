package app.afih.desktop.playback

import app.afih.telegram.MessageKey
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

internal class PlaybackStore(private val path: Path) {
    init {
        Class.forName("org.sqlite.JDBC")
        Files.createDirectories(path.toAbsolutePath().parent)
        synchronized(PlaybackStore::class.java) { connection().use { db -> db.createStatement().use {
            it.execute("""CREATE TABLE IF NOT EXISTS playback_progress (
                provider TEXT NOT NULL, chat TEXT NOT NULL, message_id INTEGER NOT NULL,
                position_ms INTEGER NOT NULL, duration_ms INTEGER NOT NULL,
                PRIMARY KEY(provider, chat, message_id))""")
            it.execute("""CREATE TABLE IF NOT EXISTS personal_media (provider TEXT,chat TEXT,message_id INTEGER,
                watched INTEGER NOT NULL DEFAULT 0,favorite INTEGER NOT NULL DEFAULT 0,watchlist INTEGER NOT NULL DEFAULT 0,
                hidden INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(provider,chat,message_id))""")
            val columns = it.executeQuery("PRAGMA table_info(playback_progress)").use { rows -> buildSet { while(rows.next()) add(rows.getString("name")) } }
            if ("updated_at" !in columns) it.execute("ALTER TABLE playback_progress ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0")
        } } }
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
        connection().use { db ->
            db.autoCommit = false
            try { db.prepareStatement("""INSERT INTO playback_progress(provider,chat,message_id,position_ms,duration_ms,updated_at) VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(provider, chat, message_id) DO UPDATE SET position_ms=excluded.position_ms, duration_ms=excluded.duration_ms,updated_at=excluded.updated_at""").use {
            it.setString(1, key.providerId); it.setString(2, key.chat); it.setLong(3, key.messageId)
            it.setLong(4, position.coerceAtMost(duration)); it.setLong(5, duration); it.setLong(6,System.currentTimeMillis()); it.executeUpdate()
            }
            if (position >= duration * .95) db.prepareStatement("INSERT INTO personal_media(provider,chat,message_id,watched) VALUES(?,?,?,1) ON CONFLICT(provider,chat,message_id) DO UPDATE SET watched=1").use {
                it.setString(1,key.providerId); it.setString(2,key.chat); it.setLong(3,key.messageId); it.executeUpdate()
            }
            db.commit()
            } catch (failure: Exception) { db.rollback(); throw failure }
        }
    }
}

internal fun resumePosition(position: Long, duration: Long): Long =
    if (position >= 5_000 && duration > 0 && position < duration * .95) position else 0
