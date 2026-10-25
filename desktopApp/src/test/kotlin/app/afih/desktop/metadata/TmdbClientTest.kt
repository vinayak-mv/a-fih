package app.afih.desktop.metadata

import app.afih.metadata.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class TmdbClientTest {
    @Test fun movieSearchUsesTitleYearAndLanguageAndHandlesMissingArtwork() = runTest {
        val client = TmdbClient(TmdbTransport { path, parameters ->
            assertEquals("/search/movie", path)
            assertEquals("A Film", parameters["query"])
            assertEquals("2021", parameters["year"])
            assertEquals("hi-IN", parameters["language"])
            Json.parseToJsonElement("""{"results":[{"id":42,"title":"A Film","original_title":"A Film","release_date":"2021-01-01","poster_path":null}]}""").jsonObject
        }, "hi-IN")
        val result = client.search(ReleaseTitle("A Film", 2021, null, null), MediaType.MOVIE).single()
        assertEquals(42L, result.id)
        assertEquals(2021, result.year)
    }

    @Test fun multiSearchIgnoresPeopleAndTvDoesNotFilterBySeasonReleaseYear() = runTest {
        val client = TmdbClient(TmdbTransport { path, parameters ->
            assertFalse("first_air_date_year" in parameters)
            if (path == "/search/multi") Json.parseToJsonElement("""{"results":[{"id":7,"media_type":"person","name":"Person"},{"id":9,"media_type":"tv","name":"Dark","original_name":"Dark","first_air_date":"2017-01-01"}]}""").jsonObject
            else buildJsonObject { putJsonArray("results") {} }
        }, "en-US")
        assertEquals(MediaType.TV, client.search(ReleaseTitle("Dark", null, null, null), null).single().type)
        client.search(ReleaseTitle("Dark", 2019, 2, 1), MediaType.TV)
    }

    @Test fun detailsDecodeCastSeasonsNullPathsAndRejectUntrustedImagePaths() {
        val details = decodeDetails(Json.parseToJsonElement("""{
            "id":9,"name":"Dark","first_air_date":"2017-12-01","overview":"A series",
            "poster_path":"/poster.jpg","backdrop_path":"https://evil.example/image.jpg",
            "genres":[{"name":"Mystery"}],"vote_average":8.5,
            "credits":{"cast":[{"name":"Actor","character":"Role","profile_path":null}]},
            "seasons":[{"season_number":1,"name":"Season 1","episode_count":10,"poster_path":null}]
        }""").jsonObject, MediaType.TV)
        assertEquals("/poster.jpg", details.posterPath)
        assertNull(details.backdropPath)
        assertEquals("Role", details.cast.single().character)
        assertEquals(10, details.seasons.single().episodeCount)
    }

    @Test fun seasonRequestsKeepEpisodeNumbersAndDescriptions() = runTest {
        val client = TmdbClient(TmdbTransport { path, _ ->
            assertEquals("/tv/9/season/2", path)
            Json.parseToJsonElement("""{"episodes":[{"episode_number":3,"name":"Episode Three","overview":"Description","still_path":"/still.jpg","air_date":"2019-01-01"}]}""").jsonObject
        }, "en-US")
        val season = client.season(9, 2)
        assertEquals(3, season.episodes.single().number)
        assertEquals("Description", season.episodes.single().overview)
    }

    @Test fun incompleteSearchResponsesAreErrorsRatherThanConfirmedNoMatches() = runTest {
        val client = TmdbClient(TmdbTransport { _, _ -> buildJsonObject {} }, "en-US")
        assertFailsWith<MetadataFailure> { client.search(ReleaseTitle("A Film", 2021, null, null), MediaType.MOVIE) }
    }
}
