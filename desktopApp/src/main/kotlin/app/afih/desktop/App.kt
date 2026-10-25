package app.afih.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.afih.model.*
import app.afih.desktop.playback.InAppPlayer
import java.util.UUID
import kotlinx.coroutines.*

private val Accent = Color(0xFFB8000B)
private val Canvas = Color(0xFFF9F9FB)

@Composable
fun AfihTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Accent,
            onPrimary = Color.White,
            primaryContainer = Color(0xFFFFDAD5),
            background = Canvas,
            surface = Color.White,
            surfaceVariant = Color(0xFFEEEEF0),
            onSurface = Color(0xFF1A1C1D),
            onSurfaceVariant = Color(0xFF5F5E5E),
        ),
        shapes = Shapes(medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp)),
        content = content,
    )
}

private enum class Page(val label: String) { HOME("Home"), LIBRARIES("Libraries"), SOURCES("Sources"), PROVIDERS("Providers"), METADATA("Metadata"), OFFLINE("Downloads"), ISSUES("Issues") }

@Composable
fun AfihApp(store: ConfigurationStore, initial: AppConfiguration, fullscreen: Boolean = false, onFullscreenChange: (Boolean) -> Unit = {}) {
    var configuration by remember { mutableStateOf(initial) }
    var page by remember { mutableStateOf(Page.HOME) }
    var providerEditor by remember { mutableStateOf(false) }
    var editingProvider by remember { mutableStateOf<TelegramProvider?>(null) }
    var libraryEditor by remember { mutableStateOf(false) }
    var editingLibrary by remember { mutableStateOf<MediaLibrary?>(null) }
    var deletingProvider by remember { mutableStateOf<TelegramProvider?>(null) }
    var deletingLibrary by remember { mutableStateOf<MediaLibrary?>(null) }
    var storageError by remember { mutableStateOf(false) }
    val service = remember { LibraryService(ConfigurationStore.defaultPath().parent) }
    val scope = rememberCoroutineScope()
    val browseScroll = rememberScrollState()
    val scans = remember { mutableStateMapOf<String, ScanUi>() }
    val jobs = remember { mutableMapOf<String, Job>() }
    val connections = remember { mutableStateMapOf<String, String>() }
    val connecting = remember { mutableStateMapOf<String, Boolean>() }
    var mediaRevision by remember { mutableStateOf(0) }
    var selectedLibrary by remember { mutableStateOf<String?>(null) }
    var operationError by remember { mutableStateOf<String?>(null) }
    var metadataJob by remember { mutableStateOf<Job?>(null) }
    var metadataBusy by remember { mutableStateOf(false) }
    var metadataStatus by remember { mutableStateOf<String?>(null) }
    var metadataGeneration by remember { mutableStateOf(0) }
    DisposableEffect(service) {
        onDispose { Thread({ service.close() }, "afih-telegram-close").start() }
    }

    fun save(next: AppConfiguration): Boolean = try {
        store.save(next)
        configuration = next
        true
    } catch (_: Exception) {
        storageError = true
        false
    }

    fun enrich(retryUnmatched: Boolean = false) {
        val snapshot = configuration
        if (snapshot.tmdb == null) return
        metadataJob?.cancel()
        val generation = ++metadataGeneration
        metadataBusy = true
        metadataStatus = "Preparing library metadata…"
        metadataJob = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val files = service.catalog(snapshot)
                    service.metadata.enrich(snapshot, files, retryUnmatched) { done, total ->
                        if (done % 10 == 0 || done == total) withContext(Dispatchers.Main) {
                            if (generation == metadataGeneration) { metadataStatus = "Checking metadata · $done / $total files"; mediaRevision++ }
                        }
                    }
                }
                if (generation == metadataGeneration) { metadataStatus = "Library metadata is up to date."; mediaRevision++ }
            } catch (cancelled: CancellationException) {
                if (generation == metadataGeneration) metadataStatus = "Metadata update paused. Saved matches have been kept."
                throw cancelled
            } catch (failure: Exception) {
                if (generation == metadataGeneration) metadataStatus = userFacingFailure(failure)
            } finally { if (generation == metadataGeneration) metadataBusy = false }
        }
    }

    fun scan(source: TelegramSource) {
        if (jobs[source.id]?.isActive == true) return
        val provider = configuration.providers.find { it.id == source.providerId } ?: return
        jobs[source.id] = scope.launch {
            var last = source.firstId - 1
            scans[source.id] = ScanUi(last, true, "Connecting…")
            try {
                withContext(Dispatchers.IO) {
                    last = service.checkpoint(source, provider)
                    withContext(Dispatchers.Main) { scans[source.id] = ScanUi(last, true, "Scanning from message ${last + 1}…") }
                    service.scan(provider, source) { checkpoint ->
                        last = checkpoint
                        withContext(Dispatchers.Main) {
                            scans[source.id] = ScanUi(checkpoint, true, "Scanned through message $checkpoint")
                            mediaRevision++
                        }
                    }
                }
                scans[source.id] = ScanUi(source.lastId, false, "Range complete through message ${source.lastId}")
                connections[provider.id] = "Connected as bot"
                mediaRevision++
                enrich()
            } catch (cancelled: CancellationException) {
                scans[source.id] = ScanUi(last, false, if (cancelled is TimeoutCancellationException) userFacingFailure(cancelled) else "Paused. Resume continues after the last saved batch.")
                if (cancelled !is TimeoutCancellationException) throw cancelled
            } catch (failure: Exception) {
                scans[source.id] = ScanUi(last, false, userFacingFailure(failure))
            }
        }
    }

    fun removeSource(source: TelegramSource) {
        if (jobs[source.id]?.isActive == true) return
        if (save(configuration.copy(sources = configuration.sources.filterNot { it.id == source.id }))) scope.launch {
            try { withContext(Dispatchers.IO) { service.removeSource(source.id) }; scans.remove(source.id); mediaRevision++ }
            catch (failure: Exception) { operationError = userFacingFailure(failure) }
        }
    }

    LaunchedEffect(Unit) { if (configuration.tmdb != null) enrich() }

    val playback by service.playback.collectAsState()
    playback?.let { session ->
        InAppPlayer(session, fullscreen, onFullscreen = { onFullscreenChange(!fullscreen) }, onBack = {
            scope.launch {
                try { withContext(Dispatchers.IO) { service.stopPlayback() }; onFullscreenChange(false) }
                catch (failure: Exception) { operationError = userFacingFailure(failure) }
            }
        })
        return
    }

    Row(Modifier.fillMaxSize().background(Canvas)) {
        Column(
            Modifier.width(200.dp).fillMaxHeight().background(Color.White).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("a-Fih", color = Accent, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(vertical = 22.dp))
            Page.entries.forEach { destination ->
                if (page == destination) {
                    Button(onClick = { page = destination }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Text(destination.label) }
                } else {
                    TextButton(onClick = { page = destination }, modifier = Modifier.fillMaxWidth()) { Text(destination.label, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            Spacer(Modifier.weight(1f))
            Text("Windows preview · 0.5.0", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(browseScroll).padding(36.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(page.label, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Text(when (page) {
                        Page.HOME -> "Your media, in one place."
                        Page.LIBRARIES -> "Organize movies, series, anime, music, and files."
                        Page.SOURCES -> "Import Telegram message ranges into your libraries."
                        Page.PROVIDERS -> "Manage your storage connections."
                        Page.METADATA -> "Posters, stories, cast, and episode information."
                        Page.OFFLINE -> "Saved media for watching without a connection."
                        Page.ISSUES -> "Review media that needs your attention."
                    }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (page == Page.PROVIDERS) Button(onClick = { editingProvider = null; providerEditor = true }) { Text("Add provider") }
                if (page == Page.LIBRARIES) Button(onClick = { editingLibrary = null; libraryEditor = true }) { Text("Create library") }
            }
            metadataStatus?.let { status ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    if (metadataBusy) TextButton(onClick = { metadataJob?.cancel() }) { Text("Pause") }
                    else if (configuration.tmdb != null) TextButton(onClick = { enrich() }) { Text("Refresh metadata") }
                }
                if (metadataBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            when (page) {
                Page.HOME -> {
                    if (configuration.sources.isEmpty()) Surface(color = Color(0xFFF3F3F5), shape = RoundedCornerShape(32.dp)) {
                        Column(Modifier.fillMaxWidth().padding(36.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text("Your Media Hub", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                            Text("Start by adding a Telegram provider and creating your libraries.")
                            Button(onClick = { page = Page.PROVIDERS }) { Text("Manage providers") }
                        }
                    }
                    Text("Library shortcuts", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    if (configuration.libraries.isEmpty()) EmptyPanel("No libraries yet", "Create a library to organize your collection.", "Create library") { editingLibrary = null; libraryEditor = true }
                    else configuration.libraries.forEach { library ->
                        InfoCard(library.name, library.kind.label(), "Open library", { selectedLibrary = library.id; page = Page.LIBRARIES })
                    }
                    Text("Your collection", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    IndexedMediaPanel(configuration, service, null, mediaRevision)
                }
                Page.PROVIDERS -> {
                    if (configuration.providers.isEmpty()) EmptyPanel("Connect your Telegram storage", "Use your own API ID, API hash, and bot token. You can manage each connection here.", "Add Telegram") { editingProvider = null; providerEditor = true }
                    configuration.providers.forEach { provider ->
                        Surface(shape = RoundedCornerShape(20.dp)) {
                            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(provider.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Text("Telegram · ${provider.parsingMode.label()}")
                                Text(connections[provider.id] ?: "Saved on this device · connection not verified", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    val providerBusy = connecting[provider.id] == true || configuration.sources.any { it.providerId == provider.id && scans[it.id]?.running == true }
                                    Button(enabled = !providerBusy, onClick = {
                                        connecting[provider.id] = true; connections[provider.id] = "Connecting…"
                                        scope.launch {
                                            try { withContext(Dispatchers.IO) { service.testConnection(provider) }; connections[provider.id] = "Connected as bot" }
                                            catch (cancelled: CancellationException) { if (cancelled is TimeoutCancellationException) connections[provider.id] = userFacingFailure(cancelled) else throw cancelled }
                                            catch (failure: Exception) { connections[provider.id] = userFacingFailure(failure) }
                                            finally { connecting[provider.id] = false }
                                        }
                                    }) { Text("Connect / check") }
                                    OutlinedButton(enabled = !providerBusy, onClick = { editingProvider = provider; providerEditor = true }) { Text("Edit") }
                                    TextButton(enabled = !providerBusy, onClick = { deletingProvider = provider }) { Text("Remove") }
                                }
                            }
                        }
                    }
                    OutlinedButton(onClick = { page = Page.SOURCES }) { Text("Manage Telegram sources") }
                }
                Page.METADATA -> MetadataSettingsPanel(configuration.tmdb, service, onSave = { settings ->
                    if (save(configuration.copy(tmdb = settings))) {
                        metadataJob?.cancel()
                        mediaRevision++
                        if (settings != null) enrich() else { metadataStatus = null; metadataBusy = false }
                        true
                    } else false
                }, onRefresh = { enrich(retryUnmatched = true) })
                Page.SOURCES -> SourcesPanel(
                    configuration, service, scans,
                    onSave = { source ->
                        val next = configuration.sources.filterNot { it.id == source.id } + source
                        save(configuration.copy(sources = next))
                    },
                    onScan = ::scan,
                    onPause = { jobs[it.id]?.cancel() },
                    onRemove = ::removeSource,
                )
                Page.LIBRARIES -> {
                    if (configuration.libraries.isEmpty()) EmptyPanel("Make room for your collection", "Create separate libraries or combine sources into one.", "Create library") { editingLibrary = null; libraryEditor = true }
                    configuration.libraries.forEach { library ->
                        Surface(shape = RoundedCornerShape(20.dp)) {
                            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(library.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Text("${library.kind.label()} · ${configuration.sources.count { library.id in it.libraryIds }} sources")
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { selectedLibrary = if (selectedLibrary == library.id) null else library.id }) { Text(if (selectedLibrary == library.id) "Close library" else "Browse") }
                                    OutlinedButton(onClick = { editingLibrary = library; libraryEditor = true }) { Text("Edit") }
                                    TextButton(onClick = { deletingLibrary = library }) { Text("Remove") }
                                }
                            }
                        }
                    }
                    configuration.libraries.find { it.id == selectedLibrary }?.let { library ->
                        Text(library.name, style = MaterialTheme.typography.headlineSmall)
                        OutlinedButton(onClick = { page = Page.SOURCES }) { Text("Manage sources") }
                        IndexedMediaPanel(configuration, service, library.id, mediaRevision)
                    }
                }
                Page.OFFLINE -> EmptyPanel("No downloads yet", "Completed downloads will form your offline library. Downloading is not available in this preview.")
                Page.ISSUES -> EmptyPanel("No media issues", "Unavailable media will appear here with retry and removal options once playback is connected.")
            }
        }
    }

    if (providerEditor) ProviderDialog(editingProvider, onDismiss = { providerEditor = false }) { provider ->
        val existing = configuration.providers.any { it.id == provider.id }
        val next = if (existing) configuration.providers.map { if (it.id == provider.id) provider else it } else configuration.providers + provider
        val previous = configuration.providers.find { it.id == provider.id }
        if (previous != null && previous.credentials != provider.credentials) {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { service.resetSession(provider.id) }
                    connections.remove(provider.id)
                    if (save(configuration.copy(providers = next))) providerEditor = false
                } catch (failure: Exception) { operationError = userFacingFailure(failure) }
            }
        } else if (save(configuration.copy(providers = next))) providerEditor = false
    }
    if (libraryEditor) LibraryDialog(editingLibrary, onDismiss = { libraryEditor = false }) { library ->
        val existing = configuration.libraries.any { it.id == library.id }
        val next = if (existing) configuration.libraries.map { if (it.id == library.id) library else it } else configuration.libraries + library
        if (save(configuration.copy(libraries = next))) libraryEditor = false
    }
    deletingProvider?.let { provider ->
        Confirmation("Remove ${provider.name}?", "Remove this connection, its saved session, and its ${configuration.sources.count { it.providerId == provider.id }} sources? Telegram files stay untouched.", { deletingProvider = null }) {
            val affected = configuration.sources.filter { it.providerId == provider.id }
            if (save(configuration.copy(providers = configuration.providers.filterNot { it.id == provider.id }, sources = configuration.sources.filterNot { it.providerId == provider.id }))) {
                deletingProvider = null
                scope.launch {
                    try { withContext(Dispatchers.IO) { service.resetSession(provider.id); affected.forEach { service.removeSource(it.id) } }; connections.remove(provider.id); mediaRevision++ }
                    catch (failure: Exception) { operationError = userFacingFailure(failure) }
                }
            }
        }
    }
    deletingLibrary?.let { library ->
        if (configuration.sources.any { library.id in it.libraryIds }) AlertDialog(
            onDismissRequest = { deletingLibrary = null }, title = { Text("This library has sources") },
            text = { Text("Remove or reassign its sources under Sources before removing the library.") },
            confirmButton = { TextButton(onClick = { deletingLibrary = null }) { Text("Close") } },
        ) else Confirmation("Remove ${library.name}?", "Remove this library from this device?", { deletingLibrary = null }) {
            if (save(configuration.copy(libraries = configuration.libraries.filterNot { it.id == library.id }))) deletingLibrary = null
        }
    }
    operationError?.let { message -> AlertDialog(
        onDismissRequest = { operationError = null }, title = { Text("Operation could not finish") }, text = { Text(message) },
        confirmButton = { TextButton(onClick = { operationError = null }) { Text("Close") } },
    ) }
    if (storageError) AlertDialog(
        onDismissRequest = { storageError = false },
        title = { Text("Settings were not saved") },
        text = { Text("Your previous settings have been kept. Check available disk space and access to your Windows application data folder, then retry.") },
        confirmButton = { TextButton(onClick = { storageError = false }) { Text("Close") } },
    )
}

@Composable
private fun ProviderDialog(provider: TelegramProvider?, onDismiss: () -> Unit, onSave: (TelegramProvider) -> Unit) {
    var name by remember { mutableStateOf(provider?.name ?: "") }
    var apiId by remember { mutableStateOf(provider?.credentials?.apiId?.toString() ?: "") }
    var apiHash by remember { mutableStateOf(provider?.credentials?.apiHash ?: "") }
    var token by remember { mutableStateOf(provider?.credentials?.botToken ?: "") }
    var parsing by remember { mutableStateOf(provider?.parsingMode ?: ParsingMode.BOTH) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (provider == null) "Add Telegram provider" else "Edit Telegram provider") },
        text = {
            Column(Modifier.width(440.dp).heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Connect with an MTProto bot account.")
                OutlinedTextField(name, { name = it }, label = { Text("Connection name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(apiId, { apiId = it }, label = { Text("API ID") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(apiHash, { apiHash = it }, label = { Text("API hash") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(token, { token = it }, label = { Text("Bot token") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Derive media information from", style = MaterialTheme.typography.labelLarge)
                ParsingMode.entries.forEach { mode ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = parsing == mode, onClick = { parsing = mode })
                        Text(mode.label())
                    }
                }
                Text("Credentials are protected by your Windows account. Saving does not verify Telegram access.", style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val result = runCatching {
                    TelegramProvider(
                        provider?.id ?: UUID.randomUUID().toString(), name.trim(),
                        TelegramCredentials(apiId.trim().toIntOrNull() ?: throw IllegalArgumentException("Enter a numeric API ID."), apiHash.trim(), token.trim()), parsing,
                    )
                }
                result.fold(onSuccess = onSave, onFailure = { error = it.message ?: "Check the connection fields." })
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun LibraryDialog(library: MediaLibrary?, onDismiss: () -> Unit, onSave: (MediaLibrary) -> Unit) {
    var name by remember { mutableStateOf(library?.name ?: "") }
    var kind by remember { mutableStateOf(library?.kind ?: LibraryKind.MOVIES) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (library == null) "Create library" else "Edit library") },
        text = {
            Column(Modifier.width(380.dp).heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Library name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LibraryKind.entries.forEach { option ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = kind == option, onClick = { kind = option })
                        Text(option.label())
                    }
                }
            }
        },
        confirmButton = { Button(enabled = name.isNotBlank(), onClick = { onSave(MediaLibrary(library?.id ?: UUID.randomUUID().toString(), name.trim(), kind)) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun EmptyPanel(title: String, description: String, action: String? = null, onAction: () -> Unit = {}) {
    Surface(shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (action != null) OutlinedButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun InfoCard(title: String, subtitle: String, action: String, onAction: () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun Confirmation(title: String, message: String, onDismiss: () -> Unit, onConfirm: () -> Unit) = AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) }, text = { Text(message) },
    confirmButton = { Button(onClick = onConfirm) { Text("Remove") } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
)

private fun ParsingMode.label(): String = when (this) { ParsingMode.FILE_NAME -> "Filename"; ParsingMode.CAPTION -> "Caption"; ParsingMode.BOTH -> "Filename and caption" }
private fun LibraryKind.label(): String = when (this) { LibraryKind.MOVIES -> "Movies"; LibraryKind.SERIES -> "TV series"; LibraryKind.ANIME -> "Anime"; LibraryKind.MUSIC -> "Music"; LibraryKind.FILES -> "Files" }
