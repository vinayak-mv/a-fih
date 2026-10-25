package app.afih.metadata

import kotlinx.serialization.Serializable

@Serializable enum class MediaType(val path: String) { MOVIE("movie"), TV("tv") }
@Serializable data class CastMember(val name: String, val character: String = "", val profilePath: String? = null)
@Serializable data class SeasonSummary(val number: Int, val name: String, val episodeCount: Int, val posterPath: String? = null)
@Serializable data class EpisodeDetails(val number: Int, val name: String, val overview: String = "", val stillPath: String? = null, val airDate: String = "")
@Serializable data class SeasonDetails(val number: Int, val episodes: List<EpisodeDetails>)
@Serializable data class TitleDetails(
    val id: Long,
    val type: MediaType,
    val title: String,
    val year: Int? = null,
    val overview: String = "",
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val genres: List<String> = emptyList(),
    val rating: Double = 0.0,
    val runtimeMinutes: Int? = null,
    val cast: List<CastMember> = emptyList(),
    val seasons: List<SeasonSummary> = emptyList(),
    val tagline: String = "",
)

data class ReleaseTitle(val title: String, val year: Int?, val season: Int?, val episode: Int?)
data class SearchCandidate(val id: Long, val type: MediaType, val title: String, val originalTitle: String, val year: Int?)

/** Strip release metadata, without turning a query such as "1917" into an empty title. */
fun parseReleaseTitle(input: String): ReleaseTitle {
    val withoutExtension = input.trim().replace(Regex("\\.(mkv|mp4|avi|mov|webm|m4v)$", RegexOption.IGNORE_CASE), "")
        .replace(Regex("^\\s*(?:\\[[^]]+\\]\\s*)+"), "")
        .replace(Regex("^@[A-Za-z0-9_]+\\s*[-:|]?\\s*"), "")
    val text = withoutExtension.replace('.', ' ').replace('_', ' ').trim()
    val episode = Regex("\\b[Ss](\\d{1,2})\\s*[Ee](\\d{1,3})\\b|\\b(\\d{1,2})[xX](\\d{1,3})\\b").find(text)
    val seasonOnly = Regex("\\b(?:[Ss]eason\\s*|[Ss])(\\d{1,2})\\b").find(text)
    val yearMatch = Regex("\\b(?:19|20)\\d{2}\\b").findAll(text).firstOrNull { text.substring(0, it.range.first).any(Char::isLetterOrDigit) }
    val technical = Regex("\\b(?:\\d{3,4}p|2160|4K|WEB[- ]?DL|WEBRip|BluRay|BDRip|BRRip|HDRip|DVDRip|HDTV|x26[45]|h[ .-]?26[45]|HEVC|AVC|AAC|DTS|DDP?\\d?|REMUX|MULTI|DUAL|10bit|HDR10?|Dolby)\\b", RegexOption.IGNORE_CASE).find(text)
    val boundary = listOfNotNull(episode?.range?.first, seasonOnly?.range?.first, yearMatch?.range?.first, technical?.range?.first).minOrNull() ?: text.length
    val title = text.take(boundary).replace(Regex("[\\[\\]()]+"), " ").trim(' ', '-', '|', ':').replace(Regex("\\s+"), " ")
    val season = episode?.let { (it.groupValues[1].ifBlank { it.groupValues[3] }).toIntOrNull() } ?: seasonOnly?.groupValues?.get(1)?.toIntOrNull()
    val episodeNumber = episode?.let { (it.groupValues[2].ifBlank { it.groupValues[4] }).toIntOrNull() }
    return ReleaseTitle(title, yearMatch?.value?.toIntOrNull(), season, episodeNumber)
}

fun normalizedTitle(value: String): String = value.lowercase().replace("&", " and ")
    .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().replace(Regex("\\s+"), " ")

/** Ambiguous remakes and loose name matches remain unmatched instead of guessing. */
fun chooseCandidate(query: ReleaseTitle, candidates: List<SearchCandidate>): SearchCandidate? {
    val title = normalizedTitle(query.title)
    if (title.isBlank()) return null
    val matches = candidates.filter {
        normalizedTitle(it.title) == title || normalizedTitle(it.originalTitle) == title
    }.distinctBy { it.type to it.id }
    if (query.year == null) return matches.singleOrNull()
    val dated = matches.filter { it.year == query.year }
    if (dated.isNotEmpty()) return dated.singleOrNull()
    // A season release can carry its own year rather than the show's premiere year.
    return matches.singleOrNull()?.takeIf {
        query.season != null && it.type == MediaType.TV && it.year != null && it.year <= query.year
    }
}
