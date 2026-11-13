package app.afih.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
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
    Column(verticalArrangement = Arrangement.spacedBy(Ui.Space.md)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Ui.Space.md)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Ui.Space.xxs)) {
                Text("Telegram sources", style = MaterialTheme.typography.titleLarge)
                Text("Each source scans a message range into the libraries you choose", style = MaterialTheme.typography.bodySmall)
            }
            Button(enabled = configuration.providers.isNotEmpty() && configuration.libraries.isNotEmpty(), onClick = { editing = null; editor = true }, colors = Buttons.primaryColors, contentPadding = Buttons.controlPadding) { Text("Add source") }
        }
        if (configuration.providers.isEmpty() || configuration.libraries.isEmpty()) {
            Text("Add a provider and create a library first, then connect a message range here.", style = MaterialTheme.typography.bodyMedium, color = Ui.TextSecondary)
        } else if (configuration.sources.isEmpty()) {
            Text("Add start and end message links. Deleted messages and empty batches are skipped within that range.", style = MaterialTheme.typography.bodyMedium, color = Ui.TextSecondary)
        }
        Column(verticalArrangement = Arrangement.spacedBy(Ui.Space.sm)) {
            configuration.sources.forEach { source ->
                val scan = scans[source.id]
                QuietCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(Ui.Space.lg), verticalArrangement = Arrangement.spacedBy(Ui.Space.sm)) {
                        Column(verticalArrangement = Arrangement.spacedBy(Ui.Space.xxs)) {
                            Text(source.name, style = MaterialTheme.typography.titleLarge)
                            Text("${configuration.providers.find { it.id == source.providerId }?.name ?: "Missing provider"} · messages ${source.firstId}–${source.lastId}" + (source.topicId?.let { " · topic $it" } ?: " · whole chat"), style = MaterialTheme.typography.bodySmall)
                            Text(configuration.libraries.filter { it.id in source.libraryIds }.joinToString { it.name }.ifBlank { "No libraries" }, style = MaterialTheme.typography.bodySmall, color = Ui.TextSecondary)
                        }
                        Text("Incoming updates ${if (source.automaticUpdates) "on" else "off"} · launch scan ${if (source.scanOnLaunch) "on" else "off"} · schedule ${if (source.scanIntervalMinutes == 0) "off" else "${source.scanIntervalMinutes} min"}", style = MaterialTheme.typography.labelMedium, color = Ui.TextMuted)
                        scan?.let {
                            val fraction = ((it.scannedThrough - source.firstId + 1).toFloat() / (source.lastId - source.firstId + 1)).coerceIn(0f, 1f)
                            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth(), color = Ui.Accent, trackColor = Ui.Raised)
                            Text(it.message, style = MaterialTheme.typography.bodySmall, color = Ui.TextSecondary)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(Ui.Space.xs)) {
                            if (scan?.running == true) OutlinedButton(onClick = { onPause(source) }, colors = Buttons.secondaryColors, border = androidx.compose.foundation.BorderStroke(1.dp, Ui.HairlineStrong), contentPadding = Buttons.controlPadding) { Text("Pause") }
                            else Button(onClick = { onScan(source) }, colors = Buttons.primaryColors, contentPadding = Buttons.controlPadding) { Text("Scan or resume") }
                            OutlinedButton(enabled = scan?.running != true, onClick = { editing = source; editor = true }, colors = Buttons.secondaryColors, border = androidx.compose.foundation.BorderStroke(1.dp, Ui.HairlineStrong), contentPadding = Buttons.controlPadding) { Text("Edit") }
                            TextButton(enabled = scan?.running != true, onClick = { removing = source }, colors = Buttons.quietColors) { Text("Remove") }
                        }
                    }
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
            confirmButton = { Button(onClick = { onRemove(source); removing = null }, colors = ButtonDefaults.buttonColors(containerColor = Ui.Raised, contentColor = Ui.Negative), contentPadding = Buttons.controlPadding) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { removing = null }, colors = Buttons.quietColors) { Text("Cancel") } },
            containerColor = Ui.Surface, titleContentColor = Ui.TextPrimary, textContentColor = Ui.TextSecondary,
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
            Column(Modifier.width(480.dp).heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Ui.Space.sm)) {
                OutlinedTextField(name, { name = it }, enabled = !busy, label = { Text("Source name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(Ui.Radius.control))
                Text("Provider", style = MaterialTheme.typography.labelLarge, color = Ui.TextSecondary)
                configuration.providers.forEach { provider -> ChoiceRow(provider.name, providerId == provider.id, !busy) { providerId = provider.id } }
                OutlinedTextField(startLink, { startLink = it }, enabled = !busy, label = { Text("Start message link") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(Ui.Radius.control))
                OutlinedTextField(endLink, { endLink = it }, enabled = !busy, label = { Text("End message link") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(Ui.Radius.control))
                CheckRow(topicOnly, "Only the topic specified in these links", !busy) { topicOnly = it }
                CheckRow(automatic, "Follow new messages and edits", !busy) { automatic = it }
                CheckRow(launchScan, "Scan on app launch", !busy) { launchScan = it }
                OutlinedTextField(interval, { interval = it }, enabled = !busy, label = { Text("Scan every N minutes (0 = off)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(Ui.Radius.control))
                Text("Schedules and incoming updates run while the app is open, including when minimized. Extend the end link manually if Telegram cannot replay an older gap.", style = MaterialTheme.typography.bodySmall, color = Ui.TextMuted)
                Text("Libraries", style = MaterialTheme.typography.labelLarge, color = Ui.TextSecondary)
                configuration.libraries.forEach { library -> CheckRow(library.id in libraries, library.name, !busy) { selected -> libraries = if (selected) libraries + library.id else libraries - library.id } }
                Text("Derive titles from", style = MaterialTheme.typography.labelLarge, color = Ui.TextSecondary)
                ChoiceRow("Provider default", parsing == null, !busy) { parsing = null }
                ParsingMode.entries.forEach { option -> ChoiceRow(option.displayName(), parsing == option, !busy) { parsing = option } }
                if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth(), color = Ui.Accent, trackColor = Ui.Raised); Text("Connecting and checking chat access…", style = MaterialTheme.typography.bodySmall) }
                error?.let { Text(it, color = Ui.Negative, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(enabled = !busy && startLink.isNotBlank() && endLink.isNotBlank() && libraries.isNotEmpty(), onClick = {
                busy = true; error = null
                val provider = configuration.providers.first { it.id == providerId }
                scope.launch {
                    try {
                        val created = withContext(Dispatchers.IO) { service.createSource(provider, name, startLink, endLink, topicOnly, libraries, parsing) }
                        val minutes = interval.toIntOrNull() ?: throw IllegalArgumentException("Enter a scan interval in minutes")
                        onSave(created.copy(id = source?.id ?: created.id, automaticUpdates = automatic, scanOnLaunch = launchScan, scanIntervalMinutes = minutes))
                    } catch (cancelled: CancellationException) { if (cancelled is TimeoutCancellationException) error = userFacingFailure(cancelled) else throw cancelled }
                    catch (failure: Exception) { error = userFacingFailure(failure) }
                    finally { busy = false }
                }
            }, colors = Buttons.primaryColors, contentPadding = Buttons.controlPadding) { Text("Save and scan") }
        },
        dismissButton = { TextButton(onClick = onDismiss, colors = Buttons.quietColors) { Text("Cancel") } },
        containerColor = Ui.Surface, titleContentColor = Ui.TextPrimary, textContentColor = Ui.TextSecondary,
    )
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(32.dp)) {
        RadioButton(selected, onSelect, enabled = enabled, colors = RadioButtonDefaults.colors(selectedColor = Ui.Accent, unselectedColor = Ui.TextMuted))
        Spacer(Modifier.width(Ui.Space.xs))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Ui.TextPrimary)
    }
}

@Composable
private fun CheckRow(checked: Boolean, label: String, enabled: Boolean, onChecked: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(32.dp)) {
        Checkbox(checked, onChecked, enabled = enabled, colors = CheckboxDefaults.colors(checkedColor = Ui.Accent, uncheckedColor = Ui.TextMuted, checkmarkColor = Ui.BgDeep))
        Spacer(Modifier.width(Ui.Space.xs))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Ui.TextPrimary)
    }
}

@Composable
fun IndexedMediaPanel(configuration: AppConfiguration, service: LibraryService, libraryId: String?, revision: Int) {
    CatalogPanel(configuration, service, libraryId, revision)
}

private fun ParsingMode.displayName() = when (this) { ParsingMode.FILE_NAME -> "Filename"; ParsingMode.CAPTION -> "Caption"; ParsingMode.BOTH -> "Filename and caption" }
