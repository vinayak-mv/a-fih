package app.afih.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.afih.desktop.index.MediaIssue
import app.afih.model.AppConfiguration
import kotlinx.coroutines.*

@Composable
fun IssuesPanel(configuration: AppConfiguration, service: LibraryService) {
    val revision by service.revision.collectAsState()
    var issues by remember { mutableStateOf<List<MediaIssue>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<MediaIssue?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(revision) {
        try { issues = withContext(Dispatchers.IO) { service.issues() } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = userFacingFailure(failure) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(Ui.Space.md)) {
        error?.let { Text(it, color = Ui.Negative, style = MaterialTheme.typography.bodySmall) }
        if (issues.isEmpty()) {
            QuietPanel(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(Ui.Space.xl), verticalArrangement = Arrangement.spacedBy(Ui.Space.xs)) {
                    Text("No recorded issues", style = MaterialTheme.typography.titleMedium)
                    Text("Playback and import problems will appear here when they need attention.", style = MaterialTheme.typography.bodyMedium, color = Ui.TextSecondary)
                }
            }
        }
        issues.forEach { issue ->
            QuietCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(Ui.Space.lg), verticalArrangement = Arrangement.spacedBy(Ui.Space.sm)) {
                    Column(verticalArrangement = Arrangement.spacedBy(Ui.Space.xxs)) {
                        Text(issue.fileName, style = MaterialTheme.typography.titleMedium)
                        Text("${issue.kind.replace('_', ' ')} · ${issue.attempts} attempt${if (issue.attempts == 1) "" else "s"}", style = MaterialTheme.typography.labelMedium, color = Ui.TextMuted)
                    }
                    Text(issue.message, style = MaterialTheme.typography.bodyMedium, color = Ui.TextSecondary)
                    Text("${configuration.providers.find { it.id == issue.key.providerId }?.name ?: "Removed provider"} · message ${issue.key.messageId}", style = MaterialTheme.typography.bodySmall, color = Ui.TextMuted, fontFamily = FontFamily.Monospace)
                    Row(horizontalArrangement = Arrangement.spacedBy(Ui.Space.xs), verticalAlignment = Alignment.CenterVertically) {
                        Button(enabled = !busy, onClick = {
                            busy = true; error = null
                            scope.launch {
                                try { withContext(Dispatchers.IO) { service.retryIssue(configuration, issue) } }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (failure: Exception) { error = userFacingFailure(failure) }
                                finally { busy = false }
                            }
                        }, colors = Buttons.primaryColors, contentPadding = Buttons.controlPadding) { Text("Play or retry") }
                        TextButton(enabled = !busy, onClick = { removing = issue }, colors = Buttons.quietColors) { Text("Remove from library") }
                    }
                }
            }
        }
    }
    removing?.let { issue -> AlertDialog(onDismissRequest = { removing = null }, title = { Text("Remove this item?") },
        text = { Text("Hide it from your local libraries and clear its issue. Telegram messages remain untouched; rescanning will not re-add this hidden item.") },
        confirmButton = { Button(onClick = {
            removing = null
            scope.launch {
                try { withContext(Dispatchers.IO) { service.removeMedia(issue.key) } }
                catch (failure: Exception) { error = userFacingFailure(failure) }
            }
        }, colors = ButtonDefaults.buttonColors(containerColor = Ui.Raised, contentColor = Ui.Negative), contentPadding = Buttons.controlPadding) { Text("Remove") } }, dismissButton = { TextButton(onClick = { removing = null }, colors = Buttons.quietColors) { Text("Cancel") } },
        containerColor = Ui.Surface, titleContentColor = Ui.TextPrimary, textContentColor = Ui.TextSecondary) }
}
