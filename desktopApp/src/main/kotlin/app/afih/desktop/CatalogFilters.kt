package app.afih.desktop

import app.afih.desktop.metadata.CatalogFile

internal enum class CollectionFilter(val label: String) { ALL("All"), IN_PROGRESS("Continue watching"), UNWATCHED("Unwatched"), WATCHED("Watched"), FAVORITES("Favorites"), WATCHLIST("Watchlist") }
internal enum class CollectionSort(val label: String) { ADDED("Recently added"), TITLE("Title A–Z"), TITLE_DESC("Title Z–A"), PLAYED("Recently played"), YEAR("Newest year"), RATING("Highest rated") }

/** Alternate qualities share watch state; series require every available episode to be watched. */
internal fun titleWatched(files: List<CatalogFile>): Boolean = files.isNotEmpty() && files.groupBy {
    val match = it.metadata
    if (match?.season != null && match.episode != null) "${match.season}:${match.episode}" else "title"
}.values.all { versions -> versions.any { it.personal.watched } }

internal fun collectionMatches(files: List<CatalogFile>, filter: CollectionFilter): Boolean = when(filter) {
    CollectionFilter.ALL -> true
    CollectionFilter.IN_PROGRESS -> continuingFiles(files).isNotEmpty()
    CollectionFilter.WATCHED -> titleWatched(files)
    CollectionFilter.UNWATCHED -> !titleWatched(files)
    CollectionFilter.FAVORITES -> files.any { it.personal.favorite }
    CollectionFilter.WATCHLIST -> files.any { it.personal.watchlist }
}

internal fun contentUnit(file: CatalogFile): String = file.metadata?.details?.let {
    "${it.type}:${it.id}:${file.metadata.season}:${file.metadata.episode}"
} ?: file.media.key.toString()

internal fun continuingFiles(files: List<CatalogFile>): List<CatalogFile> {
    val completed = files.filter { it.personal.watched }.map(::contentUnit).toSet()
    return files.filter { it.personal.inProgress && contentUnit(it) !in completed }
        .sortedByDescending { it.personal.playedAt }.distinctBy(::contentUnit)
}
