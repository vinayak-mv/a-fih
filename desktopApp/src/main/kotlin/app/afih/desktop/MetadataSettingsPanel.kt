package app.afih.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.afih.model.TmdbSettings
import kotlinx.coroutines.*

@Composable
fun MetadataSettingsPanel(settings: TmdbSettings?, service: LibraryService, onSave: (TmdbSettings?) -> Boolean, onRefresh: () -> Unit) {
    var credential by remember(settings) { mutableStateOf(settings?.credential.orEmpty()) }
    var language by remember(settings) { mutableStateOf(settings?.language ?: "en-US") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    Surface(shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("The Movie Database", style = MaterialTheme.typography.headlineSmall)
            Text("Bring posters, descriptions, cast, and episode information into your movie and series libraries.")
            OutlinedTextField(credential, { credential = it; saved = false }, enabled = !busy, label = { Text("API Read Access Token or API key") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(language, { language = it; saved = false }, enabled = !busy, label = { Text("Metadata language") }, supportingText = { Text("For example: en-US, hi-IN, or ja-JP") }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(enabled = !busy && credential.isNotBlank(), onClick = {
                    busy = true; error = null; saved = false
                    scope.launch {
                        try {
                            val next = TmdbSettings(credential.trim(), language.trim())
                            withContext(Dispatchers.IO) { service.metadata.validate(next) }
                            saved = onSave(next)
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { error = userFacingFailure(failure) }
                        finally { busy = false }
                    }
                }) { Text(if (busy) "Checking…" else "Save and fetch metadata") }
                OutlinedButton(enabled = settings != null && !busy, onClick = onRefresh) { Text("Retry unmatched") }
                TextButton(enabled = settings != null && !busy, onClick = { if (onSave(null)) { credential = ""; saved = false } }) { Text("Disconnect") }
            }
            TextButton(onClick = { uri.openUri("https://www.themoviedb.org/settings/api") }) { Text("Get your TMDB credential") }
            if (saved) Text("TMDB connected. Your library metadata is being updated.", color = MaterialTheme.colorScheme.primary)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("The credential is protected with your Windows account. Movie and series names are sent to TMDB for matching; Telegram credentials are never sent.", style = MaterialTheme.typography.bodySmall)
        }
    }
    Surface(shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Credits", style = MaterialTheme.typography.titleLarge)
            TmdbAttribution()
        }
    }
}

@Composable
fun TmdbAttribution() {
    val uri = LocalUriHandler.current
    Image(painterResource("tmdb.svg"), "TMDB", modifier = Modifier.width(110.dp).height(24.dp))
    Text("This product uses the TMDB API but is not endorsed or certified by TMDB.", style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = { uri.openUri("https://www.themoviedb.org") }) { Text("TMDB · The Movie Database") }
}
