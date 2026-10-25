package app.afih.metadata

import app.afih.model.AppConfiguration
import app.afih.model.TmdbSettings
import kotlinx.serialization.json.Json
import kotlin.test.*

class MetadataMatchingTest {
    @Test fun releaseNamesKeepTitlesAndSeparateYearsAndEpisodeNumbers() {
        assertEquals(ReleaseTitle("Dune", 2021, null, null), parseReleaseTitle("[Group] Dune.2021.1080p.WEB-DL.x265.mkv"))
        assertEquals(ReleaseTitle("Dark", null, 2, 3), parseReleaseTitle("Dark.S02E03.1080p.HEVC.mkv"))
        assertEquals(ReleaseTitle("The Office", null, 1, 2), parseReleaseTitle("The_Office_1x02_WEBRip.mp4"))
        assertEquals(ReleaseTitle("1917", 2019, null, null), parseReleaseTitle("1917.2019.1080p.mkv"))
        assertEquals("Madame Web", parseReleaseTitle("Madame.Web.2024.720p.mkv").title)
        assertEquals("千と千尋の神隠し", parseReleaseTitle("千と千尋の神隠し.2001.1080p.mkv").title)
    }

    @Test fun remakesRequireDisambiguationAndLooseMatchesAreRejected() {
        val old = SearchCandidate(1, MediaType.MOVIE, "Dune", "Dune", 1984)
        val modern = SearchCandidate(2, MediaType.MOVIE, "Dune", "Dune", 2021)
        assertEquals(modern, chooseCandidate(ReleaseTitle("Dune", 2021, null, null), listOf(old, modern)))
        assertNull(chooseCandidate(ReleaseTitle("Dune", null, null, null), listOf(old, modern)))
        assertNull(chooseCandidate(ReleaseTitle("Dune Part Two", 2021, null, null), listOf(modern)))
        assertNull(chooseCandidate(ReleaseTitle("Dune", 2022, null, null), listOf(modern)))
    }

    @Test fun laterSeasonReleaseYearsCanUseAnUnambiguousShow() {
        val show = SearchCandidate(10, MediaType.TV, "Dark", "Dark", 2017)
        assertEquals(show, chooseCandidate(ReleaseTitle("Dark", 2019, 2, 1), listOf(show)))
        assertNull(chooseCandidate(ReleaseTitle("Dark", 2019, null, null), listOf(show)))
        assertEquals("spider man", normalizedTitle("Spider-Man"))
    }

    @Test fun credentialsAreRedactedAndExistingSettingsStayCompatible() {
        val settings = TmdbSettings("private-api-token")
        assertFalse(settings.toString().contains(settings.credential))
        assertNull(Json.decodeFromString<AppConfiguration>("""{"schemaVersion":1}""").tmdb)
        assertFailsWith<IllegalArgumentException> { TmdbSettings("secret with spaces") }
    }
}
