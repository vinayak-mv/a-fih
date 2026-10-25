package app.afih.desktop.metadata

import app.afih.metadata.*
import app.afih.model.TmdbSettings
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.serialization.json.*

class MetadataFailure(message: String) : Exception(message)

internal fun interface TmdbTransport {
    suspend fun get(path: String, parameters: Map<String, String>): JsonObject
}

internal class TmdbHttpTransport(private val settings: TmdbSettings) : TmdbTransport {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val apiKey = settings.credential.matches(Regex("[0-9a-fA-F]{32}"))

    override suspend fun get(path: String, parameters: Map<String, String>): JsonObject {
        require(path.startsWith('/') && !path.contains("..") && !path.contains('?'))
        val query = (parameters + if (apiKey) mapOf("api_key" to settings.credential) else emptyMap())
            .entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
        val builder = HttpRequest.newBuilder(URI.create("https://api.themoviedb.org/3$path?$query"))
            .timeout(Duration.ofSeconds(20)).header("Accept", "application/json").GET()
        if (!apiKey) builder.header("Authorization", "Bearer ${settings.credential}")
        // Serial calls are deliberately paced; a 429 stops the job without caching a false no-match.
        delay(200)
        val response = try { http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString()).await() }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { throw MetadataFailure("TMDB could not be reached. Check your connection and retry.") }
        when (response.statusCode()) {
            200 -> Unit
            401, 403 -> throw MetadataFailure("TMDB rejected the API key or token. Check Metadata settings.")
            429 -> throw MetadataFailure("TMDB requested a pause. Wait a moment, then refresh metadata again.")
            else -> throw MetadataFailure("TMDB could not complete this request. Retry later.")
        }
        return try { Json.parseToJsonElement(response.body()).jsonObject }
        catch (_: Exception) { throw MetadataFailure("TMDB returned an unreadable response. Retry later.") }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)
}

internal class TmdbClient(private val transport: TmdbTransport, private val language: String) {
    suspend fun validate() { transport.get("/configuration", emptyMap()) }

    suspend fun search(query: ReleaseTitle, type: MediaType?): List<SearchCandidate> {
        val path = type?.path ?: "multi"
        val parameters = buildMap {
            put("query", query.title); put("language", language); put("include_adult", "false")
            if (query.year != null && type == MediaType.MOVIE) put("year", query.year.toString())
        }
        val result = transport.get("/search/$path", parameters)
        if (result["results"] !is JsonArray) throw MetadataFailure("TMDB returned incomplete search results. Retry later.")
        return result.array("results").mapNotNull { value ->
            val item = value as? JsonObject ?: return@mapNotNull null
            val candidateType = type ?: when (item.text("media_type")) { "movie" -> MediaType.MOVIE; "tv" -> MediaType.TV; else -> return@mapNotNull null }
            val titleField = if (candidateType == MediaType.MOVIE) "title" else "name"
            val dateField = if (candidateType == MediaType.MOVIE) "release_date" else "first_air_date"
            val id = item.long("id")
            if (id <= 0) return@mapNotNull null
            SearchCandidate(id, candidateType, item.text(titleField), item.text("original_$titleField"), item.text(dateField).take(4).toIntOrNull())
        }
    }

    suspend fun details(id: Long, type: MediaType): TitleDetails {
        require(id > 0)
        return decodeDetails(transport.get("/${type.path}/$id", mapOf("language" to language, "append_to_response" to "credits")), type).also {
            if (it.id != id || it.title.isBlank()) throw MetadataFailure("TMDB returned incomplete title information. Retry later.")
        }
    }

    suspend fun season(id: Long, number: Int): SeasonDetails {
        require(id > 0 && number >= 0)
        val result = transport.get("/tv/$id/season/$number", mapOf("language" to language))
        if (result["episodes"] !is JsonArray) throw MetadataFailure("TMDB returned incomplete episode information. Retry later.")
        return SeasonDetails(number, result.array("episodes").mapNotNull { raw ->
            val episode = raw as? JsonObject ?: return@mapNotNull null
            EpisodeDetails(episode.long("episode_number").toInt(), episode.text("name"), episode.text("overview"), episode.path("still_path"), episode.text("air_date"))
        })
    }
}

internal fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
internal fun JsonObject.long(key: String): Long = (get(key) as? JsonPrimitive)?.longOrNull ?: 0
internal fun JsonObject.array(key: String): JsonArray = get(key) as? JsonArray ?: JsonArray(emptyList())
internal fun JsonObject.path(key: String): String? = text(key).takeIf { it.matches(Regex("/[A-Za-z0-9._-]+")) }

internal fun decodeDetails(item: JsonObject, type: MediaType): TitleDetails {
    val movie = type == MediaType.MOVIE
    val credits = item["credits"] as? JsonObject
    return TitleDetails(
        id = item.long("id"), type = type, title = item.text(if (movie) "title" else "name"),
        year = item.text(if (movie) "release_date" else "first_air_date").take(4).toIntOrNull(),
        overview = item.text("overview"), posterPath = item.path("poster_path"), backdropPath = item.path("backdrop_path"),
        genres = item.array("genres").mapNotNull { (it as? JsonObject)?.text("name") }.filter { it.isNotBlank() },
        rating = (item["vote_average"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
        runtimeMinutes = item.long("runtime").takeIf { it > 0 }?.toInt(),
        cast = credits?.array("cast").orEmpty().take(16).mapNotNull { raw -> (raw as? JsonObject)?.let { CastMember(it.text("name"), it.text("character"), it.path("profile_path")) } },
        seasons = item.array("seasons").mapNotNull { raw -> (raw as? JsonObject)?.let { SeasonSummary(it.long("season_number").toInt(), it.text("name"), it.long("episode_count").toInt(), it.path("poster_path")) } },
        tagline = item.text("tagline"),
    )
}
