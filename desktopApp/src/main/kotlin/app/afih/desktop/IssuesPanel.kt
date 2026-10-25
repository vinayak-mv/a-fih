package app.afih.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
    error?.let { Text(it,color = MaterialTheme.colorScheme.error) }
    if(issues.isEmpty()) Text("No recorded media issues.")
    issues.forEach { issue ->
        Surface(shape=MaterialTheme.shapes.large,modifier=Modifier.fillMaxWidth()) {
            Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text(issue.fileName,style=MaterialTheme.typography.titleMedium)
                Text("${issue.kind.replace('_',' ')} · ${issue.attempts} attempt(s)",style=MaterialTheme.typography.labelMedium)
                Text(issue.message)
                Text("${configuration.providers.find { it.id == issue.key.providerId }?.name ?: "Removed provider"} · message ${issue.key.messageId}",style=MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Button(enabled = !busy,onClick = {
                        busy = true; error = null
                        scope.launch {
                            try { withContext(Dispatchers.IO) { service.retryIssue(configuration,issue) } }
                            catch(cancelled: CancellationException) { throw cancelled }
                            catch(failure: Exception) { error = userFacingFailure(failure) }
                            finally { busy = false }
                        }
                    }) { Text("Play / retry") }
                    TextButton(enabled = !busy,onClick = { removing = issue }) { Text("Remove from library") }
                }
            }
        }
    }
    removing?.let { issue -> AlertDialog(onDismissRequest = { removing = null },title = { Text("Remove this item?") },
        text = { Text("Hide it from your local libraries and clear its issue. Telegram messages remain untouched; rescanning will not re-add this hidden item.") },
        confirmButton = { Button(onClick = {
            removing = null
            scope.launch {
                try { withContext(Dispatchers.IO) { service.removeMedia(issue.key) } }
                catch(failure: Exception) { error = userFacingFailure(failure) }
            }
        }) { Text("Remove") } },dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } }) }
}
