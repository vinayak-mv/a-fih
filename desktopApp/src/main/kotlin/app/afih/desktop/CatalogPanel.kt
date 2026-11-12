package app.afih.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.afih.desktop.metadata.*
import app.afih.metadata.*
import app.afih.model.AppConfiguration
import app.afih.model.LibraryKind
import java.util.Locale
import kotlinx.coroutines.*

private data class TitleGroup(val files: List<CatalogFile>, val details: TitleDetails?) {
    val title: String get() = details?.title ?: files.first().media.title
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CatalogPanel(configuration: AppConfiguration, service: LibraryService, libraryId: String?, revision: Int) {
    var query by remember(libraryId) { mutableStateOf("") }
    var files by remember(libraryId) { mutableStateOf<List<CatalogFile>>(emptyList()) }
    var failure by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var selected by remember(libraryId) { mutableStateOf<TitleGroup?>(null) }
    var listView by remember { mutableStateOf(false) }
    var collection by remember(libraryId) { mutableStateOf(CollectionFilter.ALL) }
    var sorting by remember(libraryId) { mutableStateOf(CollectionSort.ADDED) }
    var category by remember(libraryId) { mutableStateOf<LibraryKind?>(null) }
    val serviceRevision by service.revision.collectAsState()
    val scope = rememberCoroutineScope()
    var opening by remember { mutableStateOf(false) }
    var visible by remember(libraryId, query) { mutableStateOf(60) }
    LaunchedEffect(configuration.sources, configuration.tmdb?.language, libraryId, revision, serviceRevision) {
        loading = true
        try { files = withContext(Dispatchers.IO) { service.catalog(configuration, libraryId) }; failure = null }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (problem: Exception) { failure = userFacingFailure(problem) }
        finally { loading = false }
    }
    val groups = remember(files) {
        files.groupBy { file ->
            file.metadata?.details?.let { "${it.type}:${it.id}" } ?: "file:${file.media.key}"
        }.values.map { TitleGroup(it, it.first().metadata?.details) }
    }
    val filtered = remember(groups, query, collection, sorting, category) {
        val matches = groups.filter { group ->
            (query.isBlank() || group.title.contains(query, true) || group.files.any { it.media.fileName.contains(query, true) }) &&
                collectionMatches(group.files, collection) && (category == null || group.files.any { file -> file.libraryKinds.any { it == category } })
        }
        when (sorting) {
            CollectionSort.ADDED -> matches
            CollectionSort.TITLE -> matches.sortedBy { it.title.lowercase() }
            CollectionSort.TITLE_DESC -> matches.sortedByDescending { it.title.lowercase() }
            CollectionSort.PLAYED -> matches.sortedByDescending { it.files.maxOfOrNull { file -> file.personal.playedAt } ?: 0 }
            CollectionSort.YEAR -> matches.sortedByDescending { it.details?.year ?: 0 }
            CollectionSort.RATING -> matches.sortedByDescending { it.details?.rating ?: 0.0 }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(Ui.Space.lg)) {
        if (libraryId == null && query.isBlank() && collection == CollectionFilter.ALL && category == null) groups.firstOrNull { it.details?.backdropPath != null }?.let { group ->
            CatalogHero(group.details!!, "${group.files.size} available file${if (group.files.size == 1) "" else "s"}") { selected = group }
        }
        if (libraryId == null && query.isBlank() && collection == CollectionFilter.ALL) {
            val continuing = continuingFiles(files).take(12)
            if (continuing.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(Ui.Space.sm)) {
                    Text("Continue watching", style = MaterialTheme.typography.titleLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Ui.Space.md)) {
                        continuing.forEach { file -> QuietCard {
                            Column(Modifier.width(188.dp).padding(Ui.Space.sm), verticalArrangement = Arrangement.spacedBy(Ui.Space.xs)) {
                                TmdbArtwork(file.metadata?.details?.posterPath, file.media.title, Modifier.fillMaxWidth().height(152.dp).clip(RoundedCornerShape(Ui.Radius.image)), placeholder = file.media.title.take(1))
                                Text(file.metadata?.details?.title ?: file.media.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                                file.metadata?.let { if (it.season != null && it.episode != null) Text("Season ${it.season} · Episode ${it.episode}", style = MaterialTheme.typography.bodySmall, color = Ui.TextMuted) }
                                if (file.personal.durationMs > 0) LinearProgressIndicator(progress = { (file.personal.positionMs.toDouble() / file.personal.durationMs).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = Ui.Accent, trackColor = Ui.Raised)
                                Button(enabled = !opening, onClick = {
                                    opening = true
                                    scope.launch {
                                        try { withContext(Dispatchers.IO) { service.play(configuration, file.media) } }
                                        catch (cancelled: CancellationException) { throw cancelled }
                                        catch (problem: Exception) { failure = userFacingFailure(problem) }
                                        finally { opening = false }
                                    }
                                }, colors = Buttons.primaryColors, contentPadding = Buttons.controlPadding, modifier = Modifier.fillMaxWidth()) { Text("Resume") }
                            }
                        } }
                    }
                }
            }
        }
        QuietCard(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(Ui.Space.md), verticalArrangement = Arrangement.spacedBy(Ui.Space.sm)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Ui.Space.sm)) {
                    OutlinedTextField(query, { query = it }, label = { Text("Search titles and files") }, singleLine = true, modifier = Modifier.weight(1f), shape = RoundedCornerShape(Ui.Radius.control))
                    OutlinedButton(onClick = { listView = !listView }, colors = Buttons.secondaryColors, border = androidx.compose.foundation.BorderStroke(1.dp, Ui.HairlineStrong), contentPadding = Buttons.controlPadding) { Text(if (listView) "Poster view" else "List view") }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Ui.Space.xs)) {
                    CollectionFilter.entries.forEach { item -> FilterChip(selected = collection == item, onClick = { collection = item; visible = 60 }, label = { Text(item.label) }, colors = FilterChipDefaults.filterChipColors(containerColor = Ui.Raised, labelColor = Ui.TextSecondary, selectedContainerColor = Ui.Raised, selectedLabelColor = Ui.TextPrimary), border = FilterChipDefaults.filterChipBorder(enabled = true, selected = collection == item, borderColor = Ui.Hairline, selectedBorderColor = Ui.HairlineStrong)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Ui.Space.sm), verticalAlignment = Alignment.CenterVertically) {
                    var sortMenu by remember { mutableStateOf(false) }
                    var categoryMenu by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { sortMenu = true }, colors = Buttons.secondaryColors, border = androidx.compose.foundation.BorderStroke(1.dp, Ui.HairlineStrong), contentPadding = Buttons.controlPadding) { Text(sorting.label) }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }, containerColor = Ui.Surface) { CollectionSort.entries.forEach { item -> DropdownMenuItem(text = { Text(item.label) }, onClick = { sorting = item; sortMenu = false; visible = 60 }) } }
                    }
                    Box {
                        OutlinedButton(onClick = { categoryMenu = true }, colors = Buttons.secondaryColors, border = androidx.compose.foundation.BorderStroke(1.dp, Ui.HairlineStrong), contentPadding = Buttons.controlPadding) { Text(category?.label() ?: "All categories") }
                        DropdownMenu(expanded = categoryMenu, onDismissRequest = { categoryMenu = false }, containerColor = Ui.Surface) {
                            DropdownMenuItem(text = { Text("All categories") }, onClick = { category = null; categoryMenu = false; visible = 60 })
                            LibraryKind.entries.forEach { kind -> DropdownMenuItem(text = { Text(kind.label()) }, onClick = { category = kind; categoryMenu = false; visible = 60 }) }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text("${filtered.size} titles · ${files.size} files", style = MaterialTheme.typography.labelMedium, color = Ui.TextMuted)
                }
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Ui.Accent, trackColor = Ui.Raised)
        failure?.let { Text(it, color = Ui.Negative, style = MaterialTheme.typography.bodySmall) }
        if (!loading && filtered.isEmpty() && failure == null) Text(if (files.isEmpty()) "Add a source and scan its message range to fill this library." else "No titles match your search or filters.", style = MaterialTheme.typography.bodyMedium, color = Ui.TextSecondary)
        if (configuration.tmdb == null && files.isNotEmpty()) Text("Connect TMDB in Metadata to add posters and title information.", style = MaterialTheme.typography.bodySmall, color = Ui.TextMuted)
        if (listView) Column(verticalArrangement = Arrangement.spacedBy(Ui.Space.xs)) {
            filtered.take(visible).forEach { group ->
                QuietCard(Modifier.fillMaxWidth().clickable { selected = group }) {
                    Row(Modifier.padding(Ui.Space.md), horizontalArrangement = Arrangement.spacedBy(Ui.Space.md), verticalAlignment = Alignment.CenterVertically) {
                        TmdbArtwork(group.details?.posterPath, group.title, Modifier.width(56.dp).height(84.dp).clip(RoundedCornerShape(Ui.Radius.small)), placeholder = group.title.take(1))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Ui.Space.xxs)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Ui.Space.xs)) {
                                Text(group.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                if (titleWatched(group.files)) Text("Watched", style = MaterialTheme.typography.labelSmall, color = Ui.TextMuted)
                            }
                            Text(group.details?.let(::titleFacts) ?: "Unmatched file", style = MaterialTheme.typography.bodySmall, color = Ui.TextMuted)
                            Text(group.details?.overview?.ifBlank { group.files.first().media.fileName } ?: group.files.first().media.fileName, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        }
                        Text("${group.files.size} files", style = MaterialTheme.typography.labelMedium, color = Ui.TextSecondary)
                    }
                }
            }
        } else BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = (maxWidth.value / 176f).toInt().coerceAtLeast(2)
            val width = (maxWidth - Ui.Space.md * (columns - 1)) / columns
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Ui.Space.md), verticalArrangement = Arrangement.spacedBy(Ui.Space.lg)) {
                filtered.take(visible).forEach { group ->
                    Column(Modifier.width(width).clickable { selected = group }, verticalArrangement = Arrangement.spacedBy(Ui.Space.xs)) {
                        Box {
                            TmdbArtwork(group.details?.posterPath, group.title, Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(Ui.Radius.card)), placeholder = group.title.take(1))
                            if (group.files.size > 1) Surface(color = Ui.BgDeep.copy(alpha = 0.84f), contentColor = Ui.TextPrimary, shape = RoundedCornerShape(Ui.Radius.small), border = androidx.compose.foundation.BorderStroke(1.dp, Ui.Hairline), modifier = Modifier.align(Alignment.BottomEnd).padding(Ui.Space.xs)) {
                                Text("${group.files.size} files", modifier = Modifier.padding(horizontal = Ui.Space.xs, vertical = Ui.Space.xxs), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Text(group.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                        Text(group.details?.let { listOfNotNull(it.year?.toString(), if (it.type == MediaType.TV) "Series" else "Movie").joinToString(" · ") } ?: "Unmatched", style = MaterialTheme.typography.bodySmall, color = Ui.TextMuted)
                    }
                }
            }
        }
        if (filtered.size > visible) OutlinedButton(onClick = { visible += 60 }, colors = Buttons.secondaryColors, border = androidx.compose.foundation.BorderStroke(1.dp, Ui.HairlineStrong), contentPadding = Buttons.controlPadding) { Text("Show more titles") }
        selected?.let { old ->
            val group = groups.find { current -> current.files.any { file -> old.files.any { it.media.key == file.media.key } } } ?: old
            TitleDialog(group, configuration, service) { selected = null }
        }
    }
}

@Composable
private fun CatalogHero(details: TitleDetails, subtitle: String, actionLabel: String = "View details", onOpen: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(320.dp).clip(RoundedCornerShape(Ui.Radius.panel))) {
        TmdbArtwork(details.backdropPath, "${details.title} backdrop", Modifier.matchParentSize(), size = "w780")
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(listOf(Ui.Bg.copy(alpha = 0.96f), Ui.Bg.copy(alpha = 0.72f), Color.Transparent), startX = 0f, endX = 900f)))
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, Ui.Bg.copy(alpha = 0.32f)), startY = 120f, endY = 900f)))
        Column(Modifier.align(Alignment.BottomStart).widthIn(max = 520.dp).padding(Ui.Space.xl), verticalArrangement = Arrangement.spacedBy(Ui.Space.sm)) {
            Text("Featured title", style = MaterialTheme.typography.labelMedium, color = Ui.Accent)
            Text(details.title, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(titleFacts(details), style = MaterialTheme.typography.bodySmall, color = Ui.TextSecondary)
            Text(details.overview, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = Ui.TextSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(Ui.Space.xs), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onOpen, colors = Buttons.primaryColors, contentPadding = Buttons.controlPadding) { Text(actionLabel) }
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = Ui.TextMuted)
            }
        }
    }
}

@Composable
private fun TitleDialog(group: TitleGroup, configuration: AppConfiguration, service: LibraryService, onClose: () -> Unit) {
    val details = group.details
    val scope = rememberCoroutineScope()
    var openingPlayer by remember { mutableStateOf(false) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    fun setFlag(field: String, value: Boolean, targets: List<CatalogFile> = group.files) {
        saving = true
        scope.launch {
            try { withContext(Dispatchers.IO) { service.setPersonal(targets, field, value) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { playbackError = userFacingFailure(failure) }
            finally { saving = false }
        }
    }
    val uri = LocalUriHandler.current
    var seasonNumber by remember { mutableStateOf(details?.seasons?.firstOrNull { season -> group.files.any { it.metadata?.season == season.number } }?.number ?: details?.seasons?.firstOrNull()?.number) }
    var season by remember { mutableStateOf<SeasonDetails?>(null) }
    var seasonError by remember { mutableStateOf<String?>(null) }
    var loadingSeason by remember { mutableStateOf(false) }
    LaunchedEffect(details?.id, seasonNumber) {
        season = null; seasonError = null
        val number = seasonNumber
        if (details?.type == MediaType.TV && number != null) {
            loadingSeason = true
            try { season = withContext(Dispatchers.IO) { service.metadata.season(details, number, configuration.tmdb) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { seasonError = userFacingFailure(failure) }
            finally { loadingSeason = false }
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(Ui.Radius.panel), color = Ui.Bg, contentColor = Ui.TextPrimary, border = androidx.compose.foundation.BorderStroke(1.dp, Ui.Hairline), modifier = Modifier.widthIn(max = 1040.dp).fillMaxWidth(0.94f).fillMaxHeight(0.92f)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(Ui.Space.xl), verticalArrangement = Arrangement.spacedBy(Ui.Space.lg)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Ui.Space.md)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Ui.Space.xxs)) {
                        Text(group.title, style = MaterialTheme.typography.headlineSmall)
                        Text(details?.let(::titleFacts) ?: "Unmatched file", style = MaterialTheme.typography.bodySmall, color = Ui.TextMuted)
                    }
                    TextButton(onClick = onClose, colors = Buttons.quietColors) { Text("Close") }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Ui.Space.xs)) {
                    OutlinedButton(enabled = !saving, onClick = { setFlag("watched", !titleWatched(group.files)) }, colors = Buttons.secondaryColors, border = androidx.compose.foundation.BorderStroke(1.dp, Ui.HairlineStrong), contentPadding = Buttons.controlPadding) { Text(if (titleWatched(group.files)) "Mark unwatched" else "Mark watched") }
                    val favorite = group.files.any { it.personal.favorite }
                    val watchlist = group.files.any { it.personal.watchlist }
                    OutlinedButton(enabled = !saving, onClick = { setFlag("favorite", !favorite) }, colors = Buttons.secondaryColors, border = androidx.compose.foundation.BorderStroke(1.dp, Ui.HairlineStrong), contentPadding = Buttons.controlPadding) { Text(if (favorite) "Remove favorite" else "Favorite") }
                    OutlinedButton(enabled = !saving, onClick = { setFlag("watchlist", !watchlist) }, colors = Buttons.secondaryColors, border = androidx.compose.foundation.BorderStroke(1.dp, Ui.HairlineStrong), contentPadding = Buttons.controlPadding) { Text(if (watchlist) "Remove from watchlist" else "Add to watchlist") }
                }
                if (details != null) {
                    CatalogHero(details, titleFacts(details), "View on TMDB") { uri.openUri("https://www.themoviedb.org/${details.type.path}/${details.id}") }
                    if (details.tagline.isNotBlank()) Text(details.tagline, style = MaterialTheme.typography.titleMedium, color = Ui.TextSecondary)
                    Column(verticalArrangement = Arrangement.spacedBy(Ui.Space.xs)) {
                        Text("Synopsis", style = MaterialTheme.typography.titleLarge)
                        Text(details.overview.ifBlank { "No synopsis available in the selected language." }, style = MaterialTheme.typography.bodyMedium)
                        if (details.genres.isNotEmpty()) Text(details.genres.joinToString(" · "), color = Ui.TextMuted, style = MaterialTheme.typography.bodySmall)
                    }
                    if (details.cast.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(Ui.Space.sm)) {
                            Text("Cast", style = MaterialTheme.typography.titleLarge)
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Ui.Space.md)) {
                                details.cast.forEach { cast -> Column(Modifier.width(104.dp), verticalArrangement = Arrangement.spacedBy(Ui.Space.xs)) {
                                    TmdbArtwork(cast.profilePath, cast.name, Modifier.width(104.dp).height(140.dp).clip(RoundedCornerShape(Ui.Radius.image)), size = "w185", placeholder = cast.name.take(1))
                                    Text(cast.name, maxLines = 2, style = MaterialTheme.typography.labelLarge, color = Ui.TextPrimary)
                                    Text(cast.character, maxLines = 2, style = MaterialTheme.typography.bodySmall, color = Ui.TextMuted)
                                } }
                            }
                        }
                    }
                    if (details.type == MediaType.TV && details.seasons.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(Ui.Space.sm)) {
                            Text("Seasons and episodes", style = MaterialTheme.typography.titleLarge)
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Ui.Space.xs)) {
                                details.seasons.forEach { summary -> FilterChip(selected = summary.number == seasonNumber, onClick = { seasonNumber = summary.number }, label = { Text(summary.name.ifBlank { "Season ${summary.number}" }) }, colors = FilterChipDefaults.filterChipColors(containerColor = Ui.Raised, labelColor = Ui.TextSecondary, selectedContainerColor = Ui.Raised, selectedLabelColor = Ui.TextPrimary), border = FilterChipDefaults.filterChipBorder(enabled = true, selected = summary.number == seasonNumber, borderColor = Ui.Hairline, selectedBorderColor = Ui.HairlineStrong)) }
                            }
                            if (loadingSeason) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Ui.Accent, trackColor = Ui.Raised)
                            seasonError?.let { Text(it, color = Ui.Negative, style = MaterialTheme.typography.bodySmall) }
                            Column(verticalArrangement = Arrangement.spacedBy(Ui.Space.sm)) {
                                season?.episodes?.forEach { episode ->
                                    val count = group.files.count { it.metadata?.season == seasonNumber && it.metadata?.episode == episode.number }
                                    QuietCard(Modifier.fillMaxWidth()) {
                                        Row(Modifier.fillMaxWidth().padding(Ui.Space.sm), horizontalArrangement = Arrangement.spacedBy(Ui.Space.md), verticalAlignment = Alignment.Top) {
                                            TmdbArtwork(episode.stillPath, episode.name, Modifier.width(140.dp).height(80.dp).clip(RoundedCornerShape(Ui.Radius.small)))
                                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Ui.Space.xxs)) {
                                                Text("${episode.number}. ${episode.name}", style = MaterialTheme.typography.titleSmall)
                                                Text(episode.overview.ifBlank { "No episode description available." }, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                                                Text(if (count > 0) "$count available file${if (count == 1) "" else "s"}" else "Not in your library", style = MaterialTheme.typography.labelSmall, color = if (count > 0) Ui.Accent else Ui.TextMuted)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else Text("This file has not been matched to TMDB. It remains available in your library.", style = MaterialTheme.typography.bodyMedium, color = Ui.TextSecondary)
                Column(verticalArrangement = Arrangement.spacedBy(Ui.Space.sm)) {
                    Text("Available files · ${group.files.size}", style = MaterialTheme.typography.titleLarge)
                    if (openingPlayer) { LinearProgressIndicator(Modifier.fillMaxWidth(), color = Ui.Accent, trackColor = Ui.Raised); Text("Opening stream…", style = MaterialTheme.typography.bodySmall, color = Ui.TextSecondary) }
                    playbackError?.let { Text(it, color = Ui.Negative, style = MaterialTheme.typography.bodySmall) }
                    group.files.sortedWith(compareBy({ it.metadata?.season ?: 0 }, { it.metadata?.episode ?: 0 }, { it.media.fileName })).forEach { file ->
                        QuietCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth().padding(Ui.Space.md), verticalArrangement = Arrangement.spacedBy(Ui.Space.sm)) {
                                Column(verticalArrangement = Arrangement.spacedBy(Ui.Space.xxs)) {
                                    Text(file.media.fileName, style = MaterialTheme.typography.titleSmall)
                                    Text("${file.media.mimeType.ifBlank { "Media file" }} · ${formatSize(file.media.sizeBytes)} · message ${file.media.key.messageId}", style = MaterialTheme.typography.bodySmall, color = Ui.TextMuted, fontFamily = FontFamily.Monospace)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(Ui.Space.xs), verticalAlignment = Alignment.CenterVertically) {
                                    Button(enabled = !openingPlayer, onClick = {
                                        openingPlayer = true; playbackError = null
                                        scope.launch {
                                            try {
                                                withContext(Dispatchers.IO) { service.play(configuration, file.media) }
                                                onClose()
                                            } catch (cancelled: CancellationException) { throw cancelled }
                                            catch (failure: Exception) { playbackError = userFacingFailure(failure) }
                                            finally { openingPlayer = false }
                                        }
                                    }, colors = Buttons.primaryColors, contentPadding = Buttons.controlPadding) { Text("Play") }
                                    val versions = group.files.filter { contentUnit(it) == contentUnit(file) }
                                    TextButton(enabled = !saving, onClick = { setFlag("watched", !titleWatched(versions), versions) }, colors = Buttons.quietColors) { Text(if (titleWatched(versions)) "Mark unwatched" else "Mark watched") }
                                }
                            }
                        }
                    }
                    Text("Streams use temporary storage. Offline downloads are coming in a later milestone.", style = MaterialTheme.typography.bodySmall, color = Ui.TextMuted)
                }
                if (details != null) TmdbAttribution()
            }
        }
    }
}

private fun titleFacts(details: TitleDetails): String = listOfNotNull(
    details.year?.toString(), if (details.type == MediaType.TV) "Series" else "Movie",
    details.runtimeMinutes?.let { "$it min" }, details.rating.takeIf { it > 0 }?.let { "Rating ${String.format(Locale.ROOT, "%.1f", it)}" },
).joinToString(" · ")

private fun formatSize(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) String.format(Locale.ROOT, "%.1f GB", mb / 1024.0) else String.format(Locale.ROOT, "%.0f MB", mb)
}

private fun LibraryKind.label(): String = when (this) { LibraryKind.MOVIES -> "Movies"; LibraryKind.SERIES -> "TV series"; LibraryKind.ANIME -> "Anime"; LibraryKind.MUSIC -> "Music"; LibraryKind.FILES -> "Files" }
