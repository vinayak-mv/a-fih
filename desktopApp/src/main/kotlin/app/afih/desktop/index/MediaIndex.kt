package app.afih.desktop.index

import app.afih.model.TelegramSource
import app.afih.telegram.IndexedMessage
import app.afih.telegram.MessageKey
import app.afih.telegram.ScanBatch
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/** Owns a short-lived connection per operation; transactions include media and checkpoint. */
class MediaIndex(private val path: Path) {
    init {
        Class.forName("org.sqlite.JDBC")
        Files.createDirectories(path.toAbsolutePath().parent)
        connection().use { db ->
            db.createStatement().use { statement ->
                statement.execute("""CREATE TABLE IF NOT EXISTS media (
                    provider TEXT NOT NULL, chat TEXT NOT NULL, message_id INTEGER NOT NULL,
                    file_name TEXT NOT NULL, topic_id INTEGER, caption TEXT NOT NULL,
                    mime_type TEXT NOT NULL, size_bytes INTEGER NOT NULL, remote_file_id TEXT NOT NULL,
                    title TEXT NOT NULL, indexed_at INTEGER NOT NULL,
                    PRIMARY KEY(provider, chat, message_id))""")
                statement.execute("""CREATE TABLE IF NOT EXISTS source_media (
                    source_id TEXT NOT NULL, provider TEXT NOT NULL, chat TEXT NOT NULL,
                    message_id INTEGER NOT NULL, PRIMARY KEY(source_id, provider, chat, message_id))""")
                statement.execute("""CREATE TABLE IF NOT EXISTS scan_progress (
                    source_id TEXT PRIMARY KEY, signature TEXT NOT NULL, scanned_through INTEGER NOT NULL)""")
            }
        }
    }

    private fun connection(): Connection = DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").also {
        it.createStatement().use { statement -> statement.execute("PRAGMA busy_timeout = 10000") }
    }

    fun checkpoint(source: TelegramSource, signature: String): Long = connection().use { db ->
        db.prepareStatement("SELECT scanned_through FROM scan_progress WHERE source_id = ? AND signature = ?").use { query ->
            query.setString(1, source.id); query.setString(2, signature)
            query.executeQuery().use { result -> if (result.next()) result.getLong(1).coerceIn(source.firstId - 1, source.lastId) else source.firstId - 1 }
        }
    }

    fun commit(source: TelegramSource, signature: String, batch: ScanBatch) {
        require(batch.scannedThrough in source.firstId..source.lastId)
        require(batch.messages.all { it.key.providerId == source.providerId && it.key.chat == source.chat && it.key.messageId in source.firstId..batch.scannedThrough })
        connection().use { db ->
            db.autoCommit = false
            try {
                db.prepareStatement("""INSERT INTO media VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(provider, chat, message_id) DO UPDATE SET
                    file_name=excluded.file_name, topic_id=excluded.topic_id, caption=excluded.caption,
                    mime_type=excluded.mime_type, size_bytes=excluded.size_bytes,
                    remote_file_id=excluded.remote_file_id, title=excluded.title""").use { upsert ->
                    db.prepareStatement("INSERT OR IGNORE INTO source_media VALUES (?, ?, ?, ?)").use { membership ->
                        batch.messages.forEach { media ->
                            upsert.setString(1, media.key.providerId); upsert.setString(2, media.key.chat); upsert.setLong(3, media.key.messageId)
                            upsert.setString(4, media.fileName)
                            val topicId = media.topicId
                            if (topicId == null) upsert.setNull(5, java.sql.Types.BIGINT) else upsert.setLong(5, topicId)
                            upsert.setString(6, media.caption); upsert.setString(7, media.mimeType); upsert.setLong(8, media.sizeBytes)
                            upsert.setString(9, media.remoteFileId); upsert.setString(10, media.title); upsert.setLong(11, System.currentTimeMillis())
                            upsert.executeUpdate()
                            membership.setString(1, source.id); membership.setString(2, media.key.providerId)
                            membership.setString(3, media.key.chat); membership.setLong(4, media.key.messageId); membership.executeUpdate()
                        }
                    }
                }
                db.prepareStatement("INSERT INTO scan_progress VALUES (?, ?, ?) ON CONFLICT(source_id) DO UPDATE SET signature=excluded.signature, scanned_through=excluded.scanned_through").use {
                    it.setString(1, source.id); it.setString(2, signature); it.setLong(3, batch.scannedThrough); it.executeUpdate()
                }
                db.commit()
            } catch (failure: Exception) {
                db.rollback()
                throw failure
            }
        }
    }

    fun list(sourceIds: Set<String>, limit: Int = 500, query: String = ""): List<IndexedMessage> {
        require(limit in 1..500)
        if (sourceIds.isEmpty()) return emptyList()
        return connection().use { db ->
            val placeholders = sourceIds.joinToString(",") { "?" }
            db.prepareStatement("SELECT DISTINCT m.* FROM media m JOIN source_media s USING(provider, chat, message_id) WHERE s.source_id IN ($placeholders) AND (? = '' OR instr(lower(m.title), lower(?)) > 0 OR instr(lower(m.file_name), lower(?)) > 0) ORDER BY indexed_at DESC, message_id DESC LIMIT ?").use { statement ->
                sourceIds.forEachIndexed { i, source -> statement.setString(i + 1, source) }
                for (offset in 1..3) statement.setString(sourceIds.size + offset, query.trim())
                statement.setInt(sourceIds.size + 4, limit)
                statement.executeQuery().use { rows -> buildList {
                    while (rows.next()) add(IndexedMessage(
                        MessageKey(rows.getString("provider"), rows.getString("chat"), rows.getLong("message_id")),
                        rows.getString("file_name"), rows.getLong("topic_id").let { if (rows.wasNull()) null else it },
                        rows.getString("caption"), rows.getString("mime_type"), rows.getLong("size_bytes"),
                        rows.getString("remote_file_id"), rows.getString("title"),
                    ))
                } }
            }
        }
    }

    /** Forget index associations only; never touches remote media or downloads. */
    fun removeSource(sourceId: String) = connection().use { db ->
        db.autoCommit = false
        try {
            for (table in listOf("source_media", "scan_progress")) db.prepareStatement("DELETE FROM $table WHERE source_id = ?").use {
                it.setString(1, sourceId); it.executeUpdate()
            }
            db.createStatement().use { it.executeUpdate("DELETE FROM media WHERE NOT EXISTS (SELECT 1 FROM source_media s WHERE s.provider=media.provider AND s.chat=media.chat AND s.message_id=media.message_id)") }
            db.commit()
        } catch (failure: Exception) { db.rollback(); throw failure }
    }
}
