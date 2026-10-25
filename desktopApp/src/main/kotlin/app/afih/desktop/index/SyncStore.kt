package app.afih.desktop.index

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import app.afih.telegram.MessageKey

data class PendingMessage(val key: MessageKey, val kind: String, val version: Long)
data class SyncRun(val lastSuccess: Long = 0, val retryAt: Long = 0, val failures: Int = 0)

/** Watermarks, edit/delete work and retry times survive process termination. */
class SyncStore(private val path: Path) {
    init {
        Class.forName("org.sqlite.JDBC"); Files.createDirectories(path.toAbsolutePath().parent)
        db().use { connection -> connection.createStatement().use {
            it.execute("CREATE TABLE IF NOT EXISTS chat_watermarks(provider TEXT,chat TEXT,last_id INTEGER NOT NULL,PRIMARY KEY(provider,chat))")
            it.execute("CREATE TABLE IF NOT EXISTS pending_messages(provider TEXT,chat TEXT,message_id INTEGER,kind TEXT NOT NULL,version INTEGER NOT NULL,PRIMARY KEY(provider,chat,message_id))")
            it.execute("CREATE TABLE IF NOT EXISTS sync_runs(source_id TEXT PRIMARY KEY,last_success INTEGER NOT NULL DEFAULT 0,retry_at INTEGER NOT NULL DEFAULT 0,failures INTEGER NOT NULL DEFAULT 0)")
            it.execute("CREATE TABLE IF NOT EXISTS provider_cooldowns(provider TEXT PRIMARY KEY,retry_at INTEGER NOT NULL)")
        } }
    }
    private fun db() = DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").also { connection -> connection.createStatement().use { it.execute("PRAGMA busy_timeout=10000") } }
    fun watermark(provider: String, chat: String, id: Long) {
        require(id in 1..Int.MAX_VALUE.toLong())
        db().use { connection -> connection.prepareStatement("INSERT INTO chat_watermarks VALUES(?,?,?) ON CONFLICT(provider,chat) DO UPDATE SET last_id=max(last_id,excluded.last_id)").use {
            it.setString(1,provider); it.setString(2,chat); it.setLong(3,id); it.executeUpdate()
        } }
    }
    fun latest(provider: String, chat: String): Long = db().use { connection -> connection.prepareStatement("SELECT last_id FROM chat_watermarks WHERE provider=? AND chat=?").use {
        it.setString(1,provider); it.setString(2,chat); it.executeQuery().use { rows -> if(rows.next()) rows.getLong(1) else 0 }
    } }
    fun enqueue(key: MessageKey, kind: String) = db().use { connection ->
        connection.prepareStatement("INSERT INTO pending_messages VALUES(?,?,?,?,1) ON CONFLICT(provider,chat,message_id) DO UPDATE SET kind=excluded.kind,version=version+1").use {
            it.setString(1,key.providerId); it.setString(2,key.chat); it.setLong(3,key.messageId); it.setString(4,kind); it.executeUpdate()
        }
    }
    fun pending(provider: String, limit: Int = 100): List<PendingMessage> = db().use { connection ->
        connection.prepareStatement("SELECT * FROM pending_messages WHERE provider=? ORDER BY chat,message_id LIMIT ?").use {
            it.setString(1,provider); it.setInt(2,limit); it.executeQuery().use { rows -> buildList {
                while(rows.next()) add(PendingMessage(MessageKey(provider,rows.getString("chat"),rows.getLong("message_id")),rows.getString("kind"),rows.getLong("version")))
            } }
        }
    }
    fun acknowledge(item: PendingMessage) = db().use { connection ->
        connection.prepareStatement("DELETE FROM pending_messages WHERE provider=? AND chat=? AND message_id=? AND version=?").use {
            it.setString(1,item.key.providerId); it.setString(2,item.key.chat); it.setLong(3,item.key.messageId); it.setLong(4,item.version); it.executeUpdate()
        }
    }
    fun run(sourceId: String): SyncRun = db().use { connection -> connection.prepareStatement("SELECT * FROM sync_runs WHERE source_id=?").use {
        it.setString(1,sourceId); it.executeQuery().use { rows -> if(rows.next()) SyncRun(rows.getLong("last_success"),rows.getLong("retry_at"),rows.getInt("failures")) else SyncRun() }
    } }
    fun success(sourceId: String, now: Long) = db().use { connection -> connection.prepareStatement("INSERT INTO sync_runs VALUES(?,?,0,0) ON CONFLICT(source_id) DO UPDATE SET last_success=excluded.last_success,retry_at=0,failures=0").use {
        it.setString(1,sourceId); it.setLong(2,now); it.executeUpdate()
    } }
    fun failure(sourceId: String, retryAt: Long) = db().use { connection -> connection.prepareStatement("INSERT INTO sync_runs VALUES(?,0,?,1) ON CONFLICT(source_id) DO UPDATE SET retry_at=excluded.retry_at,failures=failures+1").use {
        it.setString(1,sourceId); it.setLong(2,retryAt); it.executeUpdate()
    } }
    fun cooldown(provider: String): Long = db().use { connection -> connection.prepareStatement("SELECT retry_at FROM provider_cooldowns WHERE provider=?").use {
        it.setString(1,provider); it.executeQuery().use { rows -> if(rows.next()) rows.getLong(1) else 0 }
    } }
    fun deferProvider(provider: String, retryAt: Long) = db().use { connection -> connection.prepareStatement("INSERT INTO provider_cooldowns VALUES(?,?) ON CONFLICT(provider) DO UPDATE SET retry_at=max(retry_at,excluded.retry_at)").use {
        it.setString(1,provider); it.setLong(2,retryAt); it.executeUpdate()
    } }
}
