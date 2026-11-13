package app.afih.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.material3.*
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
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
    Column(verticalArrangement = Arrangement.spacedBy(Ui.Space.md)) {
        QuietPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(Ui.Space.xl), verticalArrangement = Arrangement.spacedBy(Ui.Space.md)) {
                Column(verticalArrangement = Arrangement.spacedBy(Ui.Space.xxs)) {
                    Text("The Movie Database", style = MaterialTheme.typography.headlineSmall)
                    Text("Add posters, descriptions, cast, and episode information to your movie and series libraries.", style = MaterialTheme.typography.bodyMedium, color = Ui.TextSecondary)
                }
                OutlinedTextField(credential, { credential = it; saved = false }, enabled = !busy, label = { Text("API Read Access Token or API key") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(Ui.Radius.control))
                OutlinedTextField(language, { language = it; saved = false }, enabled = !busy, label = { Text("Metadata language") }, supportingText = { Text("For example: en-US, hi-IN, or ja-JP") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(Ui.Radius.control))
                Row(horizontalArrangement = Arrangement.spacedBy(Ui.Space.xs), verticalAlignment = Alignment.CenterVertically) {
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
                    }, colors = Buttons.primaryColors, contentPadding = Buttons.controlPadding) { Text(if (busy) "Checking…" else "Save and fetch metadata") }
                    OutlinedButton(enabled = settings != null && !busy, onClick = onRefresh, colors = Buttons.secondaryColors, border = androidx.compose.foundation.BorderStroke(1.dp, Ui.HairlineStrong), contentPadding = Buttons.controlPadding) { Text("Retry unmatched") }
                    TextButton(enabled = settings != null && !busy, onClick = { if (onSave(null)) { credential = ""; saved = false } }, colors = Buttons.quietColors) { Text("Disconnect") }
                }
                TextButton(onClick = { uri.openUri("https://www.themoviedb.org/settings/api") }, colors = Buttons.accentText, contentPadding = Buttons.controlPadding) { Text("Get your TMDB credential") }
                if (saved) Text("TMDB connected. Your library metadata is being updated.", color = Ui.Positive, style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = Ui.Negative, style = MaterialTheme.typography.bodySmall) }
                Text("The credential is protected with your Windows account. Movie and series names are sent to TMDB for matching; Telegram credentials are never sent.", style = MaterialTheme.typography.bodySmall, color = Ui.TextMuted)
            }
        }
        QuietCard(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(Ui.Space.xl), verticalArrangement = Arrangement.spacedBy(Ui.Space.sm)) {
                Text("Credits", style = MaterialTheme.typography.titleLarge)
                TmdbAttribution()
            }
        }
    }
}

@Composable
fun TmdbAttribution() {
    val uri = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(Ui.Space.xs)) {
        Image(painterResource("tmdb.svg"), "TMDB", modifier = Modifier.width(100.dp).height(20.dp))
        Text("This product uses the TMDB API but is not endorsed or certified by TMDB.", style = MaterialTheme.typography.bodySmall, color = Ui.TextSecondary)
        TextButton(onClick = { uri.openUri("https://www.themoviedb.org") }, colors = Buttons.quietColors, contentPadding = PaddingValues(0.dp)) { Text("TMDB · The Movie Database") }
    }
}
