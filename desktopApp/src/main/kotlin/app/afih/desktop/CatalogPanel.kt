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
    val filtered = remember(groups, query,collection,sorting,category) {
        val matches = groups.filter { group ->
            (query.isBlank() || group.title.contains(query,true) || group.files.any { it.media.fileName.contains(query,true) }) &&
                collectionMatches(group.files,collection) && (category == null || group.files.any { file -> file.libraryKinds.any { it == category } })
        }
        when(sorting) {
            CollectionSort.ADDED -> matches
            CollectionSort.TITLE -> matches.sortedBy { it.title.lowercase() }
            CollectionSort.TITLE_DESC -> matches.sortedByDescending { it.title.lowercase() }
            CollectionSort.PLAYED -> matches.sortedByDescending { it.files.maxOfOrNull { file -> file.personal.playedAt } ?: 0 }
            CollectionSort.YEAR -> matches.sortedByDescending { it.details?.year ?: 0 }
            CollectionSort.RATING -> matches.sortedByDescending { it.details?.rating ?: 0.0 }
        }
    }
    if (libraryId == null && query.isBlank() && collection == CollectionFilter.ALL && category == null) groups.firstOrNull { it.details?.backdropPath != null }?.let { group ->
        CatalogHero(group.details!!, "${group.files.size} available file${if (group.files.size == 1) "" else "s"}") { selected = group }
    }
    if(libraryId == null && query.isBlank() && collection == CollectionFilter.ALL) {
        val continuing = continuingFiles(files).take(12)
        if(continuing.isNotEmpty()) {
            Text("Continue watching",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                continuing.forEach { file -> Surface(shape=MaterialTheme.shapes.medium) {
                    Column(Modifier.width(180.dp).padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        TmdbArtwork(file.metadata?.details?.posterPath,file.media.title,Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(12.dp)),placeholder=file.media.title.take(1))
                        Text(file.metadata?.details?.title ?: file.media.title,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleSmall)
                        file.metadata?.let { if(it.season != null && it.episode != null) Text("S${it.season} · E${it.episode}",style=MaterialTheme.typography.bodySmall) }
                        LinearProgressIndicator(progress={ (file.personal.positionMs.toDouble()/file.personal.durationMs).toFloat().coerceIn(0f,1f) },modifier=Modifier.fillMaxWidth())
                        Button(enabled = !opening,onClick = {
                            opening = true
                            scope.launch {
                                try { withContext(Dispatchers.IO) { service.play(configuration,file.media) } }
                                catch(cancelled: CancellationException) { throw cancelled }
                                catch(problem: Exception) { failure = userFacingFailure(problem) }
                                finally { opening = false }
                            }
                        }) { Text("Resume") }
                    }
                } }
            }
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(query, { query = it }, label = { Text("Search your library") }, singleLine = true, modifier = Modifier.weight(1f))
        TextButton(onClick = { listView = !listView }) { Text(if (listView) "Poster view" else "List view") }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        CollectionFilter.entries.forEach { item -> FilterChip(selected=collection == item,onClick={ collection=item; visible=60 },label={ Text(item.label) }) }
    }
    Row(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
        var sortMenu by remember { mutableStateOf(false) }
        var categoryMenu by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick={ sortMenu=true }) { Text(sorting.label) }
            DropdownMenu(expanded=sortMenu,onDismissRequest={ sortMenu=false }) { CollectionSort.entries.forEach { item -> DropdownMenuItem(text={ Text(item.label) },onClick={ sorting=item; sortMenu=false; visible=60 }) } }
        }
        Box {
            OutlinedButton(onClick={ categoryMenu=true }) { Text(category?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "All categories") }
            DropdownMenu(expanded=categoryMenu,onDismissRequest={ categoryMenu=false }) {
                DropdownMenuItem(text={ Text("All categories") },onClick={ category=null; categoryMenu=false; visible=60 })
                LibraryKind.entries.forEach { kind -> DropdownMenuItem(text={ Text(kind.name.lowercase().replaceFirstChar { it.uppercase() }) },onClick={ category=kind; categoryMenu=false; visible=60 }) }
            }
        }
    }
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Text("${filtered.size} titles · ${files.size} files", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (!loading && filtered.isEmpty() && failure == null) Text(if (files.isEmpty()) "Add a source and scan its message range to fill this library." else "No titles match your search.")
    if (configuration.tmdb == null && files.isNotEmpty()) Text("Connect TMDB in Metadata to add posters and title information.", style = MaterialTheme.typography.bodySmall)
    if (listView) filtered.take(visible).forEach { group ->
        Surface(shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().clickable { selected = group }) {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                TmdbArtwork(group.details?.posterPath, group.title, Modifier.width(58.dp).height(87.dp).clip(RoundedCornerShape(8.dp)), placeholder = group.title.take(1))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(group.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if(titleWatched(group.files)) Text("Watched",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
                    Text(group.details?.let(::titleFacts) ?: "Unmatched file", style = MaterialTheme.typography.bodySmall)
                    Text(group.details?.overview?.ifBlank { group.files.first().media.fileName } ?: group.files.first().media.fileName, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text("${group.files.size} files", style = MaterialTheme.typography.labelMedium)
            }
        }
    } else BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = (maxWidth.value / 175f).toInt().coerceAtLeast(1)
        val width = (maxWidth - 16.dp * (columns - 1)) / columns
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            filtered.take(visible).forEach { group ->
                Column(Modifier.width(width).clickable { selected = group }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box {
                        TmdbArtwork(group.details?.posterPath, group.title, Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(16.dp)), placeholder = group.title.take(1))
                        if (group.files.size > 1) Surface(color = Color.Black.copy(alpha = .72f), contentColor = Color.White, shape = RoundedCornerShape(8.dp), modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)) {
                            Text("${group.files.size} files", modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Text(group.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(group.details?.let { listOfNotNull(it.year?.toString(), if (it.type == MediaType.TV) "Series" else "Movie").joinToString(" · ") } ?: "Unmatched", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (filtered.size > visible) OutlinedButton(onClick = { visible += 60 }) { Text("Show more titles") }
    selected?.let { old ->
        val group = groups.find { current -> current.files.any { file -> old.files.any { it.media.key == file.media.key } } } ?: old
        TitleDialog(group, configuration, service) { selected = null }
    }
}

@Composable
private fun CatalogHero(details: TitleDetails, subtitle: String, actionLabel: String = "View details", onOpen: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(310.dp).clip(RoundedCornerShape(28.dp))) {
        TmdbArtwork(details.backdropPath, "${details.title} backdrop", Modifier.matchParentSize(), size = "w780")
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(listOf(Color(0xFFF3F3F5), Color(0xFFF3F3F5).copy(alpha = .92f), Color.White.copy(alpha = .08f)))))
        Column(Modifier.align(Alignment.CenterStart).widthIn(max = 480.dp).padding(30.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(details.title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(titleFacts(details), style = MaterialTheme.typography.labelLarge)
            Text(details.overview, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Button(onClick = onOpen) { Text(actionLabel) }
            Text(subtitle, style = MaterialTheme.typography.labelSmall)
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
            try { withContext(Dispatchers.IO) { service.setPersonal(targets,field,value) } }
            catch(cancelled: CancellationException) { throw cancelled }
            catch(failure: Exception) { playbackError=userFacingFailure(failure) }
            finally { saving=false }
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
        Surface(shape = RoundedCornerShape(28.dp), modifier = Modifier.widthIn(max = 1050.dp).fillMaxWidth(.94f).fillMaxHeight(.94f)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(28.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(group.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClose) { Text("Close") }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !saving,onClick={ setFlag("watched",!titleWatched(group.files)) }) { Text(if(titleWatched(group.files)) "Mark unwatched" else "Mark watched") }
                    val favorite = group.files.any { it.personal.favorite }
                    val watchlist = group.files.any { it.personal.watchlist }
                    OutlinedButton(enabled = !saving,onClick={ setFlag("favorite",!favorite) }) { Text(if(favorite) "Unfavorite" else "Favorite") }
                    OutlinedButton(enabled = !saving,onClick={ setFlag("watchlist",!watchlist) }) { Text(if(watchlist) "Remove from watchlist" else "Add to watchlist") }
                }
                if (details != null) {
                    CatalogHero(details, titleFacts(details), "View on TMDB") { uri.openUri("https://www.themoviedb.org/${details.type.path}/${details.id}") }
                    if (details.tagline.isNotBlank()) Text(details.tagline, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Text("Synopsis", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(details.overview.ifBlank { "No synopsis available in the selected language." })
                    if (details.genres.isNotEmpty()) Text(details.genres.joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (details.cast.isNotEmpty()) {
                        Text("Cast", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            details.cast.forEach { cast -> Column(Modifier.width(105.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                TmdbArtwork(cast.profilePath, cast.name, Modifier.width(105.dp).height(140.dp).clip(RoundedCornerShape(12.dp)), size = "w185", placeholder = cast.name.take(1))
                                Text(cast.name, maxLines = 2, style = MaterialTheme.typography.labelLarge)
                                Text(cast.character, maxLines = 2, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } }
                        }
                    }
                    if (details.type == MediaType.TV && details.seasons.isNotEmpty()) {
                        Text("Seasons and episodes", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            details.seasons.forEach { summary -> FilterChip(selected = summary.number == seasonNumber, onClick = { seasonNumber = summary.number }, label = { Text(summary.name.ifBlank { "Season ${summary.number}" }) }) }
                        }
                        if (loadingSeason) LinearProgressIndicator(Modifier.fillMaxWidth())
                        seasonError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        season?.episodes?.forEach { episode ->
                            val count = group.files.count { it.metadata?.season == seasonNumber && it.metadata?.episode == episode.number }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                TmdbArtwork(episode.stillPath, episode.name, Modifier.width(140.dp).height(80.dp).clip(RoundedCornerShape(10.dp)))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text("${episode.number}. ${episode.name}", style = MaterialTheme.typography.titleSmall)
                                    Text(episode.overview, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                                    Text(if (count > 0) "$count available file${if (count == 1) "" else "s"}" else "Not in your library", style = MaterialTheme.typography.labelSmall, color = if (count > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                } else Text("This file has not been matched to TMDB. It remains available in your library.")
                Text("Available files · ${group.files.size}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (openingPlayer) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Opening Telegram stream…") }
                playbackError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                group.files.sortedWith(compareBy({ it.metadata?.season ?: 0 }, { it.metadata?.episode ?: 0 }, { it.media.fileName })).forEach { file ->
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(file.media.fileName, style = MaterialTheme.typography.titleSmall)
                            Text("${file.media.mimeType.ifBlank { "Media file" }} · ${file.media.sizeBytes / (1024 * 1024)} MB · message ${file.media.key.messageId}", style = MaterialTheme.typography.bodySmall)
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
                            }) { Text("Play") }
                            val versions = group.files.filter { contentUnit(it) == contentUnit(file) }
                            TextButton(enabled = !saving,onClick={ setFlag("watched",!titleWatched(versions),versions) }) { Text(if(titleWatched(versions)) "Mark unwatched" else "Mark watched") }
                        }
                    }
                }
                Text("Streams use temporary storage. Offline downloads are coming in a later milestone.", style = MaterialTheme.typography.bodySmall)
                if (details != null) TmdbAttribution()
            }
        }
    }
}

private fun titleFacts(details: TitleDetails): String = listOfNotNull(
    details.year?.toString(), if (details.type == MediaType.TV) "Series" else "Movie",
    details.runtimeMinutes?.let { "$it min" }, details.rating.takeIf { it > 0 }?.let { "★ ${String.format(Locale.ROOT, "%.1f", it)}" },
).joinToString(" · ")
