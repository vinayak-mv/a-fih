package app.afih.desktop.metadata

import app.afih.metadata.*
import app.afih.telegram.MessageKey
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class FileMetadata(val signature: String, val details: TitleDetails?, val season: Int?, val episode: Int?)

/** Metadata tables are additive; existing Telegram catalog rows are left intact. */
class MetadataStore(private val path: Path) {
    init {
        Class.forName("org.sqlite.JDBC")
        Files.createDirectories(path.toAbsolutePath().parent)
        connection().use { db -> db.createStatement().use {
            it.execute("""CREATE TABLE IF NOT EXISTS title_metadata (
                type TEXT NOT NULL, tmdb_id INTEGER NOT NULL, language TEXT NOT NULL,
                payload TEXT NOT NULL, PRIMARY KEY(type, tmdb_id, language))""")
            it.execute("""CREATE TABLE IF NOT EXISTS file_metadata (
                provider TEXT NOT NULL, chat TEXT NOT NULL, message_id INTEGER NOT NULL,
                language TEXT NOT NULL, signature TEXT NOT NULL, type TEXT, tmdb_id INTEGER,
                season INTEGER, episode INTEGER, PRIMARY KEY(provider, chat, message_id, language))""")
            it.execute("""CREATE TABLE IF NOT EXISTS season_metadata (
                tmdb_id INTEGER NOT NULL, season INTEGER NOT NULL, language TEXT NOT NULL,
                payload TEXT NOT NULL, PRIMARY KEY(tmdb_id, season, language))""")
        } }
    }

    private fun connection() = DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").also {
        it.createStatement().use { statement -> statement.execute("PRAGMA busy_timeout = 10000") }
    }

    fun read(keys: List<MessageKey>, language: String): Map<MessageKey, FileMetadata> = connection().use { db ->
        db.prepareStatement("""SELECT f.signature, f.season, f.episode, t.payload FROM file_metadata f
            LEFT JOIN title_metadata t ON t.type=f.type AND t.tmdb_id=f.tmdb_id AND t.language=f.language
            WHERE f.provider=? AND f.chat=? AND f.message_id=? AND f.language=?""").use { query -> buildMap {
            keys.forEach { key ->
                query.setString(1, key.providerId); query.setString(2, key.chat); query.setLong(3, key.messageId); query.setString(4, language)
                query.executeQuery().use { row -> if (row.next()) {
                    val season = row.getInt("season").let { if (row.wasNull()) null else it }
                    val episode = row.getInt("episode").let { if (row.wasNull()) null else it }
                    put(key, FileMetadata(row.getString("signature"), row.getString("payload")?.let { Json.decodeFromString<TitleDetails>(it) }, season, episode))
                } }
            }
        } }
    }

    fun save(key: MessageKey, language: String, metadata: FileMetadata) = connection().use { db ->
        db.autoCommit = false
        try {
            val details = metadata.details
            if (details != null) db.prepareStatement("INSERT INTO title_metadata VALUES (?, ?, ?, ?) ON CONFLICT(type, tmdb_id, language) DO UPDATE SET payload=excluded.payload").use {
                it.setString(1, details.type.name); it.setLong(2, details.id); it.setString(3, language); it.setString(4, Json.encodeToString(details)); it.executeUpdate()
            }
            db.prepareStatement("INSERT INTO file_metadata VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(provider, chat, message_id, language) DO UPDATE SET signature=excluded.signature, type=excluded.type, tmdb_id=excluded.tmdb_id, season=excluded.season, episode=excluded.episode").use {
                it.setString(1, key.providerId); it.setString(2, key.chat); it.setLong(3, key.messageId); it.setString(4, language); it.setString(5, metadata.signature)
                it.setString(6, details?.type?.name)
                if (details == null) it.setNull(7, java.sql.Types.BIGINT) else it.setLong(7, details.id)
                if (metadata.season == null) it.setNull(8, java.sql.Types.INTEGER) else it.setInt(8, metadata.season)
                if (metadata.episode == null) it.setNull(9, java.sql.Types.INTEGER) else it.setInt(9, metadata.episode)
                it.executeUpdate()
            }
            db.commit()
        } catch (failure: Exception) { db.rollback(); throw failure }
    }

    fun details(type: MediaType, id: Long, language: String): TitleDetails? = connection().use { db ->
        db.prepareStatement("SELECT payload FROM title_metadata WHERE type=? AND tmdb_id=? AND language=?").use {
            it.setString(1, type.name); it.setLong(2, id); it.setString(3, language)
            it.executeQuery().use { row -> if (row.next()) Json.decodeFromString<TitleDetails>(row.getString(1)) else null }
        }
    }

    fun season(id: Long, number: Int, language: String): SeasonDetails? = connection().use { db ->
        db.prepareStatement("SELECT payload FROM season_metadata WHERE tmdb_id=? AND season=? AND language=?").use {
            it.setLong(1, id); it.setInt(2, number); it.setString(3, language)
            it.executeQuery().use { row -> if (row.next()) Json.decodeFromString<SeasonDetails>(row.getString(1)) else null }
        }
    }

    fun saveSeason(id: Long, language: String, season: SeasonDetails) = connection().use { db ->
        db.prepareStatement("INSERT INTO season_metadata VALUES (?, ?, ?, ?) ON CONFLICT(tmdb_id, season, language) DO UPDATE SET payload=excluded.payload").use {
            it.setLong(1, id); it.setInt(2, season.number); it.setString(3, language); it.setString(4, Json.encodeToString(season)); it.executeUpdate()
        }
    }
}
