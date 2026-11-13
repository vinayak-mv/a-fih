package app.afih.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.afih.model.*
import app.afih.desktop.playback.InAppPlayer
import java.util.UUID
import kotlinx.coroutines.*

private enum class Page(val label: String) {
    HOME("Home"), LIBRARIES("Libraries"), SOURCES("Sources"), PROVIDERS("Providers"), METADATA("Metadata"), OFFLINE("Downloads"), ISSUES("Issues")
}

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
    val backgroundScans by service.sync.collectAsState()
    val scope = rememberCoroutineScope()
    val browseScroll = rememberScrollState()
    val scans = remember { mutableStateMapOf<String, ScanUi>() }
    val displayedScans = backgroundScans + scans.filterValues { it.running }
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
                if (generation == metadataGeneration) { metadataStatus = "Library metadata is up to date"; mediaRevision++ }
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
                    withContext(Dispatchers.Main) { scans[source.id] = ScanUi(last, true, "Scanning from message ${last + 1}") }
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
    LaunchedEffect(configuration) { service.configure(configuration) }

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

    Row(Modifier.fillMaxSize().background(Ui.Bg)) {
        Column(
            Modifier.width(Ui.sidebarWidth).fillMaxHeight().background(Ui.BgDeep).border(width = 1.dp, color = Ui.Hairline).padding(horizontal = Ui.Space.md, vertical = Ui.Space.lg),
            verticalArrangement = Arrangement.spacedBy(Ui.Space.xxs),
        ) {
            Column(Modifier.padding(horizontal = Ui.Space.xs, vertical = Ui.Space.sm), verticalArrangement = Arrangement.spacedBy(Ui.Space.xxs)) {
                Text("a-Fih", style = MaterialTheme.typography.titleLarge, color = Ui.TextPrimary)
                Text("Personal library", style = MaterialTheme.typography.labelSmall, color = Ui.TextMuted)
            }
            Spacer(Modifier.height(Ui.Space.sm))
            Page.entries.forEach { destination ->
                NavItem(label = destination.label, selected = page == destination) { page = destination }
            }
            Spacer(Modifier.weight(1f))
            Text("Windows preview · 0.6.0", style = MaterialTheme.typography.labelSmall, color = Ui.TextMuted, modifier = Modifier.padding(horizontal = Ui.Space.xs, vertical = Ui.Space.xs))
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = Ui.Space.xxl, vertical = Ui.Space.xl),
                verticalArrangement = Arrangement.spacedBy(Ui.Space.sm),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Ui.Space.md)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Ui.Space.xxs)) {
                        Text(page.label, style = MaterialTheme.typography.headlineMedium)
                        Text(
                            when (page) {
                                Page.HOME -> "Your collection, sources, and progress in one calm view"
                                Page.LIBRARIES -> "Organize movies, series, anime, music, and files"
                                Page.SOURCES -> "Import Telegram message ranges into your libraries"
                                Page.PROVIDERS -> "Manage storage connections on this device"
                                Page.METADATA -> "Posters, stories, cast, and episode information"
                                Page.OFFLINE -> "Saved media for watching without a connection"
                                Page.ISSUES -> "Media that needs attention"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = Ui.TextSecondary,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Ui.Space.xs), verticalAlignment = Alignment.CenterVertically) {
                        if (page == Page.PROVIDERS) Button(onClick = { editingProvider = null; providerEditor = true }, colors = Buttons.primaryColors, contentPadding = Buttons.controlPadding) { Text("Add provider") }
                        if (page == Page.LIBRARIES) Button(onClick = { editingLibrary = null; libraryEditor = true }, colors = Buttons.primaryColors, contentPadding = Buttons.controlPadding) { Text("Create library") }
                    }
                }
                metadataStatus?.let { status ->
                    QuietCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(Ui.Space.md), verticalArrangement = Arrangement.spacedBy(Ui.Space.sm)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Ui.Space.sm)) {
                                Text(status, style = MaterialTheme.typography.bodySmall, color = Ui.TextSecondary, modifier = Modifier.weight(1f))
                                if (metadataBusy) TextButton(onClick = { metadataJob?.cancel() }, colors = Buttons.quietColors) { Text("Pause") }
                                else if (configuration.tmdb != null) TextButton(onClick = { enrich() }, colors = Buttons.quietColors) { Text("Refresh metadata") }
                            }
                            if (metadataBusy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Ui.Accent, trackColor = Ui.Raised)
                        }
                    }
                }
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(browseScroll).padding(start = Ui.Space.xxl, end = Ui.Space.xxl, bottom = Ui.Space.xxl),
                verticalArrangement = Arrangement.spacedBy(Ui.Space.xl),
            ) {
                when (page) {
                    Page.HOME -> {
                        if (configuration.sources.isEmpty()) {
                            QuietPanel(Modifier.fillMaxWidth()) {
                                Column(Modifier.fillMaxWidth().padding(Ui.Space.xl), verticalArrangement = Arrangement.spacedBy(Ui.Space.md)) {
                                    Text("Your personal archive", style = MaterialTheme.typography.headlineSmall)
                                    Text("Add a Telegram provider, create a library, and import a message range to start building your collection.", style = MaterialTheme.typography.bodyMedium)
                                    Row(horizontalArrangement = Arrangement.spacedBy(Ui.Space.xs)) {
                                        Button(onClick = { page = Page.PROVIDERS }, colors = Buttons.primaryColors, contentPadding = Buttons.controlPadding) { Text("Manage providers") }
                                        OutlinedButton(onClick = { page = Page.LIBRARIES }, colors = Buttons.secondaryColors, border = androidx.compose.foundation.BorderStroke(1.dp, Ui.HairlineStrong), contentPadding = Buttons.controlPadding) { Text("Libraries") }
                                    }
                                }
                            }
                        }
                        SectionTitle("Library shortcuts")
                        if (configuration.libraries.isEmpty()) EmptyPanel("No libraries yet", "Create a library to organize your collection", "Create library") { editingLibrary = null; libraryEditor = true }
                        else Column(verticalArrangement = Arrangement.spacedBy(Ui.Space.sm)) {
                            configuration.libraries.forEach { library ->
                                InfoCard(library.name, library.kind.label(), "Open library") { selectedLibrary = library.id; page = Page.LIBRARIES }
                            }
                        }
                        SectionTitle("Collection")
                        IndexedMediaPanel(configuration, service, null, mediaRevision)
                    }
                    Page.PROVIDERS -> {
                        if (configuration.providers.isEmpty()) EmptyPanel("Connect your Telegram storage", "Use your own API ID, API hash, and bot token. Connections stay on this device.", "Add provider") { editingProvider = null; providerEditor = true }
                        Column(verticalArrangement = Arrangement.spacedBy(Ui.Space.sm)) {
                            configuration.providers.forEach { provider ->
                                QuietCard(Modifier.fillMaxWidth()) {
                                    Column(Modifier.fillMaxWidth().padding(Ui.Space.lg), verticalArrangement = Arrangement.spacedBy(Ui.Space.sm)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Ui.Space.sm)) {
                                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Ui.Space.xxs)) {
                                                Text(provider.name, style = MaterialTheme.typography.titleLarge)
                                                Text("Telegram · ${provider.parsingMode.label()}", style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                        Text(connections[provider.id] ?: "Saved on this device · connection not verified", style = MaterialTheme.typography.bodySmall, color = Ui.TextSecondary)
                                        Row(horizontalArrangement = Arrangement.spacedBy(Ui.Space.xs)) {
                                            val providerBusy = connecting[provider.id] == true || configuration.sources.any { it.providerId == provider.id && displayedScans[it.id]?.running == true }
                                            Button(enabled = !providerBusy, onClick = {
                                                connecting[provider.id] = true; connections[provider.id] = "Connecting…"
                                                scope.launch {
                                                    try { withContext(Dispatchers.IO) { service.testConnection(provider) }; connections[provider.id] = "Connected as bot" }
                                                    catch (cancelled: CancellationException) { if (cancelled is TimeoutCancellationException) connections[provider.id] = userFacingFailure(cancelled) else throw cancelled }
                                                    catch (failure: Exception) { connections[provider.id] = userFacingFailure(failure) }
                                                    finally { connecting[provider.id] = false }
                                                }
                                            }, colors = Buttons.primaryColors, contentPadding = Buttons.controlPadding) { Text("Connect and check") }
                                            OutlinedButton(enabled = !providerBusy, onClick = { editingProvider = provider; providerEditor = true }, colors = Buttons.secondaryColors, border = androidx.compose.foundation.BorderStroke(1.dp, Ui.HairlineStrong), contentPadding = Buttons.controlPadding) { Text("Edit") }
                                            TextButton(enabled = !providerBusy, onClick = { deletingProvider = provider }, colors = Buttons.quietColors) { Text("Remove") }
                                        }
                                    }
                                }
                            }
                        }
                        OutlinedButton(onClick = { page = Page.SOURCES }, colors = Buttons.secondaryColors, border = androidx.compose.foundation.BorderStroke(1.dp, Ui.HairlineStrong), contentPadding = Buttons.controlPadding) { Text("Manage sources") }
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
                        configuration, service, displayedScans,
                        onSave = { source ->
                            val next = configuration.sources.filterNot { it.id == source.id } + source
                            save(configuration.copy(sources = next))
                        },
                        onScan = ::scan,
                        onPause = { jobs[it.id]?.cancel(); service.pauseScans(it.id) },
                        onRemove = ::removeSource,
                    )
                    Page.LIBRARIES -> {
                        if (configuration.libraries.isEmpty()) EmptyPanel("Make room for your collection", "Create separate libraries or combine sources into one", "Create library") { editingLibrary = null; libraryEditor = true }
                        Column(verticalArrangement = Arrangement.spacedBy(Ui.Space.sm)) {
                            configuration.libraries.forEach { library ->
                                QuietCard(Modifier.fillMaxWidth()) {
                                    Column(Modifier.fillMaxWidth().padding(Ui.Space.lg), verticalArrangement = Arrangement.spacedBy(Ui.Space.sm)) {
                                        Text(library.name, style = MaterialTheme.typography.titleLarge)
                                        Text("${library.kind.label()} · ${configuration.sources.count { library.id in it.libraryIds }} sources", style = MaterialTheme.typography.bodySmall)
                                        Row(horizontalArrangement = Arrangement.spacedBy(Ui.Space.xs)) {
                                            Button(onClick = { selectedLibrary = if (selectedLibrary == library.id) null else library.id }, colors = Buttons.primaryColors, contentPadding = Buttons.controlPadding) { Text(if (selectedLibrary == library.id) "Close library" else "Browse") }
                                            OutlinedButton(onClick = { editingLibrary = library; libraryEditor = true }, colors = Buttons.secondaryColors, border = androidx.compose.foundation.BorderStroke(1.dp, Ui.HairlineStrong), contentPadding = Buttons.controlPadding) { Text("Edit") }
                                            TextButton(onClick = { deletingLibrary = library }, colors = Buttons.quietColors) { Text("Remove") }
                                        }
                                    }
                                }
                            }
                        }
                        configuration.libraries.find { it.id == selectedLibrary }?.let { library ->
                            SectionTitle(library.name)
                            OutlinedButton(onClick = { page = Page.SOURCES }, colors = Buttons.secondaryColors, border = androidx.compose.foundation.BorderStroke(1.dp, Ui.HairlineStrong), contentPadding = Buttons.controlPadding) { Text("Manage sources") }
                            IndexedMediaPanel(configuration, service, library.id, mediaRevision)
                        }
                    }
                    Page.OFFLINE -> EmptyPanel("No downloads yet", "Completed downloads will appear here. Downloading is not available in this preview")
                    Page.ISSUES -> IssuesPanel(configuration, service)
                }
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
        Confirmation("Remove ${provider.name}?", "Remove this connection, its saved session, and its ${configuration.sources.count { it.providerId == provider.id }} sources? Telegram files stay untouched.", "Remove", { deletingProvider = null }) {
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
            confirmButton = { TextButton(onClick = { deletingLibrary = null }, colors = Buttons.quietColors) { Text("Close") } },
            containerColor = Ui.Surface, titleContentColor = Ui.TextPrimary, textContentColor = Ui.TextSecondary,
        ) else Confirmation("Remove ${library.name}?", "Remove this library from this device?", "Remove", { deletingLibrary = null }) {
            if (save(configuration.copy(libraries = configuration.libraries.filterNot { it.id == library.id }))) deletingLibrary = null
        }
    }
    operationError?.let { message -> AlertDialog(
        onDismissRequest = { operationError = null }, title = { Text("Operation could not finish") }, text = { Text(message) },
        confirmButton = { TextButton(onClick = { operationError = null }, colors = Buttons.quietColors) { Text("Close") } },
        containerColor = Ui.Surface, titleContentColor = Ui.TextPrimary, textContentColor = Ui.TextSecondary,
    ) }
    if (storageError) AlertDialog(
        onDismissRequest = { storageError = false },
        title = { Text("Settings were not saved") },
        text = { Text("Your previous settings have been kept. Check available disk space and access to your Windows application data folder, then retry.") },
        confirmButton = { TextButton(onClick = { storageError = false }, colors = Buttons.quietColors) { Text("Close") } },
        containerColor = Ui.Surface, titleContentColor = Ui.TextPrimary, textContentColor = Ui.TextSecondary,
    )
}

@Composable
private fun NavItem(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) Ui.Raised else Color.Transparent
    val border = if (selected) Ui.HairlineStrong else Color.Transparent
    val text = if (selected) Ui.TextPrimary else Ui.TextSecondary
    Row(
        Modifier.fillMaxWidth().height(Ui.controlHeight).clip(RoundedCornerShape(Ui.Radius.control)).background(bg).border(1.dp, border, RoundedCornerShape(Ui.Radius.control)).clickable(onClick = onClick).padding(horizontal = Ui.Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) { Text(label, style = MaterialTheme.typography.bodyMedium, color = text, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, color = Ui.TextPrimary)
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
            Column(Modifier.width(440.dp).heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Ui.Space.sm)) {
                Text("Connect with an MTProto bot account", style = MaterialTheme.typography.bodySmall)
                DialogField(name, { name = it }, "Connection name")
                OutlinedTextField(apiId, { apiId = it }, label = { Text("API ID") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(Ui.Radius.control))
                OutlinedTextField(apiHash, { apiHash = it }, label = { Text("API hash") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(Ui.Radius.control))
                OutlinedTextField(token, { token = it }, label = { Text("Bot token") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(Ui.Radius.control))
                Text("Derive media information from", style = MaterialTheme.typography.labelLarge, color = Ui.TextSecondary)
                ParsingMode.entries.forEach { mode ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(32.dp)) {
                        RadioButton(selected = parsing == mode, onClick = { parsing = mode }, colors = RadioButtonDefaults.colors(selectedColor = Ui.Accent, unselectedColor = Ui.TextMuted))
                        Spacer(Modifier.width(Ui.Space.xs))
                        Text(mode.label(), style = MaterialTheme.typography.bodyMedium, color = Ui.TextPrimary)
                    }
                }
                Text("Credentials are protected by your Windows account. Saving does not verify Telegram access.", style = MaterialTheme.typography.bodySmall, color = Ui.TextMuted)
                error?.let { Text(it, color = Ui.Negative, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val result = runCatching {
                    TelegramProvider(
                        provider?.id ?: UUID.randomUUID().toString(), name.trim(),
                        TelegramCredentials(apiId.trim().toIntOrNull() ?: throw IllegalArgumentException("Enter a numeric API ID"), apiHash.trim(), token.trim()), parsing,
                    )
                }
                result.fold(onSuccess = onSave, onFailure = { error = it.message ?: "Check the connection fields" })
            }, colors = Buttons.primaryColors, contentPadding = Buttons.controlPadding) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss, colors = Buttons.quietColors) { Text("Cancel") } },
        containerColor = Ui.Surface, titleContentColor = Ui.TextPrimary, textContentColor = Ui.TextSecondary,
    )
}

@Composable
private fun DialogField(value: String, onValueChange: (String) -> Unit, label: String) {
    OutlinedTextField(value, onValueChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(Ui.Radius.control))
}

@Composable
private fun LibraryDialog(library: MediaLibrary?, onDismiss: () -> Unit, onSave: (MediaLibrary) -> Unit) {
    var name by remember { mutableStateOf(library?.name ?: "") }
    var kind by remember { mutableStateOf(library?.kind ?: LibraryKind.MOVIES) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (library == null) "Create library" else "Edit library") },
        text = {
            Column(Modifier.width(400.dp).heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Ui.Space.sm)) {
                DialogField(name, { name = it }, "Library name")
                LibraryKind.entries.forEach { option ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(32.dp)) {
                        RadioButton(selected = kind == option, onClick = { kind = option }, colors = RadioButtonDefaults.colors(selectedColor = Ui.Accent, unselectedColor = Ui.TextMuted))
                        Spacer(Modifier.width(Ui.Space.xs))
                        Text(option.label(), style = MaterialTheme.typography.bodyMedium, color = Ui.TextPrimary)
                    }
                }
            }
        },
        confirmButton = { Button(enabled = name.isNotBlank(), onClick = { onSave(MediaLibrary(library?.id ?: UUID.randomUUID().toString(), name.trim(), kind)) }, colors = Buttons.primaryColors, contentPadding = Buttons.controlPadding) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss, colors = Buttons.quietColors) { Text("Cancel") } },
        containerColor = Ui.Surface, titleContentColor = Ui.TextPrimary, textContentColor = Ui.TextSecondary,
    )
}

@Composable
private fun EmptyPanel(title: String, description: String, action: String? = null, onAction: () -> Unit = {}) {
    QuietPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(Ui.Space.xl), verticalArrangement = Arrangement.spacedBy(Ui.Space.sm)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = Ui.TextSecondary)
            if (action != null) {
                Spacer(Modifier.height(Ui.Space.xxs))
                OutlinedButton(onClick = onAction, colors = Buttons.secondaryColors, border = androidx.compose.foundation.BorderStroke(1.dp, Ui.HairlineStrong), contentPadding = Buttons.controlPadding) { Text(action) }
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, subtitle: String, action: String, onAction: () -> Unit) {
    QuietCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(Ui.Space.lg), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Ui.Space.md)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Ui.Space.xxs)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Ui.TextSecondary)
            }
            TextButton(onClick = onAction, colors = Buttons.quietColors) { Text(action) }
        }
    }
}

@Composable
private fun Confirmation(title: String, message: String, confirmLabel: String, onDismiss: () -> Unit, onConfirm: () -> Unit) = AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) }, text = { Text(message) },
    confirmButton = { Button(onClick = onConfirm, colors = ButtonDefaults.buttonColors(containerColor = Ui.Raised, contentColor = Ui.Negative), contentPadding = Buttons.controlPadding) { Text(confirmLabel) } },
    dismissButton = { TextButton(onClick = onDismiss, colors = Buttons.quietColors) { Text("Cancel") } },
    containerColor = Ui.Surface, titleContentColor = Ui.TextPrimary, textContentColor = Ui.TextSecondary,
)

private fun ParsingMode.label(): String = when (this) { ParsingMode.FILE_NAME -> "Filename"; ParsingMode.CAPTION -> "Caption"; ParsingMode.BOTH -> "Filename and caption" }
private fun LibraryKind.label(): String = when (this) { LibraryKind.MOVIES -> "Movies"; LibraryKind.SERIES -> "TV series"; LibraryKind.ANIME -> "Anime"; LibraryKind.MUSIC -> "Music"; LibraryKind.FILES -> "Files" }
