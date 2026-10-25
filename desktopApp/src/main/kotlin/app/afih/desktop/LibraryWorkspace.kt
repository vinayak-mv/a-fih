package app.afih.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.afih.model.*
import kotlinx.coroutines.*

data class ScanUi(val scannedThrough: Long, val running: Boolean, val message: String)

@Composable
fun SourcesPanel(
    configuration: AppConfiguration,
    service: LibraryService,
    scans: Map<String, ScanUi>,
    onSave: (TelegramSource) -> Boolean,
    onScan: (TelegramSource) -> Unit,
    onPause: (TelegramSource) -> Unit,
    onRemove: (TelegramSource) -> Unit,
) {
    var editor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<TelegramSource?>(null) }
    var removing by remember { mutableStateOf<TelegramSource?>(null) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Telegram sources", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        Button(enabled = configuration.providers.isNotEmpty() && configuration.libraries.isNotEmpty(), onClick = { editing = null; editor = true }) { Text("Add source") }
    }
    if (configuration.providers.isEmpty() || configuration.libraries.isEmpty()) {
        Text("Add a provider and create a library first, then connect a message range here.")
    } else if (configuration.sources.isEmpty()) {
        Text("Add start and end message links. Deleted messages and empty batches will be skipped within that range.")
    }
    configuration.sources.forEach { source ->
        val scan = scans[source.id]
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(source.name, style = MaterialTheme.typography.titleLarge)
                Text("${configuration.providers.find { it.id == source.providerId }?.name ?: "Missing provider"} · messages ${source.firstId}–${source.lastId}" + (source.topicId?.let { " · topic $it" } ?: " · whole chat"))
                Text(configuration.libraries.filter { it.id in source.libraryIds }.joinToString { it.name })
                Text("Incoming updates: ${if(source.automaticUpdates) "on" else "off"} · launch scan: ${if(source.scanOnLaunch) "on" else "off"} · schedule: ${if(source.scanIntervalMinutes == 0) "off" else "${source.scanIntervalMinutes} min"}", style = MaterialTheme.typography.bodySmall)
                scan?.let {
                    val fraction = ((it.scannedThrough - source.firstId + 1).toFloat() / (source.lastId - source.firstId + 1)).coerceIn(0f, 1f)
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    Text(it.message, style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (scan?.running == true) OutlinedButton(onClick = { onPause(source) }) { Text("Pause") }
                    else Button(onClick = { onScan(source) }) { Text("Scan / resume") }
                    OutlinedButton(enabled = scan?.running != true, onClick = { editing = source; editor = true }) { Text("Edit") }
                    TextButton(enabled = scan?.running != true, onClick = { removing = source }) { Text("Remove") }
                }
            }
        }
    }
    if (editor) SourceDialog(configuration, service, editing, { editor = false }) { source ->
        if (onSave(source)) { editor = false; onScan(source) }
    }
    removing?.let { source ->
        AlertDialog(
            onDismissRequest = { removing = null }, title = { Text("Remove ${source.name}?") },
            text = { Text("Unlink this source from its libraries and remove its scan progress. Telegram files will stay untouched.") },
            confirmButton = { Button(onClick = { onRemove(source); removing = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SourceDialog(configuration: AppConfiguration, service: LibraryService, source: TelegramSource?, onDismiss: () -> Unit, onSave: (TelegramSource) -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(source?.name.orEmpty()) }
    var providerId by remember { mutableStateOf(source?.providerId ?: configuration.providers.first().id) }
    var startLink by remember { mutableStateOf(source?.startLink.orEmpty()) }
    var endLink by remember { mutableStateOf(source?.endLink.orEmpty()) }
    var topicOnly by remember { mutableStateOf(source?.topicId != null) }
    var parsing by remember { mutableStateOf(source?.parsingMode) }
    var automatic by remember { mutableStateOf(source?.automaticUpdates ?: true) }
    var launchScan by remember { mutableStateOf(source?.scanOnLaunch ?: true) }
    var interval by remember { mutableStateOf((source?.scanIntervalMinutes ?: 15).toString()) }
    var libraries by remember { mutableStateOf(source?.libraryIds ?: emptySet()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (source == null) "Add Telegram source" else "Edit Telegram source") },
        text = {
            Column(Modifier.width(480.dp).heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, enabled = !busy, label = { Text("Source name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Provider", style = MaterialTheme.typography.labelLarge)
                configuration.providers.forEach { provider -> ChoiceRow(provider.name, providerId == provider.id, !busy) { providerId = provider.id } }
                OutlinedTextField(startLink, { startLink = it }, enabled = !busy, label = { Text("Start message link") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(endLink, { endLink = it }, enabled = !busy, label = { Text("End message link") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(topicOnly, { topicOnly = it }, enabled = !busy); Text("Only the topic specified in these links") }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(automatic,{ automatic = it },enabled = !busy); Text("Follow new messages and edits") }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(launchScan,{ launchScan = it },enabled = !busy); Text("Scan on app launch") }
                OutlinedTextField(interval,{ interval = it },enabled = !busy,label = { Text("Scan every N minutes (0 = off)") },singleLine = true)
                Text("Schedules and incoming updates run while the app is open, including when minimized. Extend the end link manually if Telegram cannot replay an older gap.",style = MaterialTheme.typography.bodySmall)
                Text("Libraries", style = MaterialTheme.typography.labelLarge)
                configuration.libraries.forEach { library ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(library.id in libraries, { selected -> libraries = if (selected) libraries + library.id else libraries - library.id }, enabled = !busy)
                        Text(library.name)
                    }
                }
                Text("Derive titles from", style = MaterialTheme.typography.labelLarge)
                ChoiceRow("Provider default", parsing == null, !busy) { parsing = null }
                ParsingMode.entries.forEach { option -> ChoiceRow(option.displayName(), parsing == option, !busy) { parsing = option } }
                if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Connecting and checking chat access…") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { Button(enabled = !busy && startLink.isNotBlank() && endLink.isNotBlank() && libraries.isNotEmpty(), onClick = {
            busy = true; error = null
            val provider = configuration.providers.first { it.id == providerId }
            scope.launch {
                try {
                    val created = withContext(Dispatchers.IO) { service.createSource(provider, name, startLink, endLink, topicOnly, libraries, parsing) }
                    val minutes = interval.toIntOrNull() ?: throw IllegalArgumentException("Enter a scan interval in minutes.")
                    onSave(created.copy(id=source?.id ?: created.id,automaticUpdates=automatic,scanOnLaunch=launchScan,scanIntervalMinutes=minutes))
                } catch (cancelled: CancellationException) { if (cancelled is TimeoutCancellationException) error = userFacingFailure(cancelled) else throw cancelled }
                catch (failure: Exception) { error = userFacingFailure(failure) }
                finally { busy = false }
            }
        }) { Text("Save and scan") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selected, onSelect, enabled = enabled); Text(label) }
}

@Composable
fun IndexedMediaPanel(configuration: AppConfiguration, service: LibraryService, libraryId: String?, revision: Int) {
    CatalogPanel(configuration, service, libraryId, revision)
}

private fun ParsingMode.displayName() = when (this) { ParsingMode.FILE_NAME -> "Filename"; ParsingMode.CAPTION -> "Caption"; ParsingMode.BOTH -> "Filename and caption" }
