package app.afih.desktop.metadata

import app.afih.metadata.*
import app.afih.model.*
import app.afih.telegram.IndexedMessage
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import app.afih.desktop.index.PersonalState

data class CatalogFile(val media: IndexedMessage, val metadata: FileMetadata?, val libraryKinds: Set<LibraryKind> = emptySet(), val personal: PersonalState = PersonalState())

internal fun queryFor(file: IndexedMessage): ReleaseTitle {
    val preferred = parseReleaseTitle(file.title)
    val filename = parseReleaseTitle(file.fileName)
    return preferred.copy(
        title = preferred.title.ifBlank { filename.title }, year = preferred.year ?: filename.year,
        season = preferred.season ?: filename.season, episode = preferred.episode ?: filename.episode,
    )
}

class MetadataService internal constructor(
    private val store: MetadataStore,
    private val clientFactory: (TmdbSettings) -> TmdbClient = { TmdbClient(TmdbHttpTransport(it), it.language) },
) {
    private val enrichment = Mutex()

    suspend fun validate(settings: TmdbSettings) { client(settings).validate() }

    fun catalog(files: List<IndexedMessage>, settings: TmdbSettings?): List<CatalogFile> {
        val cached = store.read(files.map { it.key }, settings?.language ?: "en-US")
        return files.map { CatalogFile(it, cached[it.key]) }
    }

    /** A second scan can request enrichment again; cached signatures make it cheap. */
    suspend fun enrich(
        configuration: AppConfiguration,
        files: List<CatalogFile>,
        retryUnmatched: Boolean = false,
        onProgress: suspend (Int, Int) -> Unit,
    ) {
        val settings = configuration.tmdb ?: return
        enrichment.lock()
        try {
            val client = client(settings)
            val cached = store.read(files.map { it.media.key }, settings.language)
            val queries = mutableMapOf<Pair<ReleaseTitle, MediaType?>, TitleDetails?>()
            files.forEachIndexed { index, entry ->
                currentCoroutineContext().ensureActive()
                val file = entry.media
                val kinds = entry.libraryKinds
                val query = queryFor(file)
                val eligible = kinds.any { it in setOf(LibraryKind.MOVIES, LibraryKind.SERIES, LibraryKind.ANIME) } && !file.mimeType.startsWith("audio/")
                val type = when {
                    query.season != null -> MediaType.TV
                    LibraryKind.SERIES in kinds && LibraryKind.MOVIES !in kinds && LibraryKind.ANIME !in kinds -> MediaType.TV
                    LibraryKind.MOVIES in kinds && LibraryKind.SERIES !in kinds && LibraryKind.ANIME !in kinds -> MediaType.MOVIE
                    else -> null
                }
                val signature = "v1|${file.title}|${file.fileName}|${query}|$type|$eligible"
                val previous = cached[file.key]
                if (previous?.signature != signature || (retryUnmatched && previous?.details == null)) {
                    var details: TitleDetails? = null
                    if (eligible && query.title.isNotBlank()) {
                        // Episode numbers don't affect show matching; share one lookup across a season.
                        val search = query.copy(season = null, episode = null)
                        val key = search to type
                        if (!queries.containsKey(key)) {
                            val match = chooseCandidate(query, client.search(search, type))
                            queries[key] = match?.let { store.details(it.type, it.id, settings.language) ?: client.details(it.id, it.type) }
                        }
                        details = queries[key]
                    }
                    currentCoroutineContext().ensureActive()
                    store.save(file.key, settings.language, FileMetadata(signature, details, query.season, query.episode))
                }
                onProgress(index + 1, files.size)
            }
        } finally { enrichment.unlock() }
    }

    suspend fun season(details: TitleDetails, number: Int, settings: TmdbSettings?): SeasonDetails? {
        val language = settings?.language ?: "en-US"
        return store.season(details.id, number, language) ?: settings?.let {
            client(it).season(details.id, number).also { result -> store.saveSeason(details.id, language, result) }
        }
    }

    private fun client(settings: TmdbSettings) = clientFactory(settings)
}
