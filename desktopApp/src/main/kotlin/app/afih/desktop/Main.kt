package app.afih.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.window.WindowPlacement

fun main() = application {
    val opened = remember { runCatching { ConfigurationStore(ConfigurationStore.defaultPath()).let { it to it.load() } } }
    val windowState = rememberWindowState(width = 1200.dp, height = 820.dp)
    Window(
        title = "a-Fih",
        onCloseRequest = ::exitApplication,
        state = windowState,
    ) {
        AfihTheme {
            val state = opened.getOrNull()
            if (state == null) {
                Column(Modifier.fillMaxSize().padding(48.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Your settings could not be opened", style = MaterialTheme.typography.headlineMedium)
                    Text("The saved file has been preserved. This build requires Windows and access to the Windows account that saved these settings. Close the app before restoring a backup or checking the file in %LOCALAPPDATA%\\a-Fih.")
                }
            } else {
                AfihApp(state.first, state.second, windowState.placement == WindowPlacement.Fullscreen) {
                    windowState.placement = if (it) WindowPlacement.Fullscreen else WindowPlacement.Floating
                }
            }
        }
    }
}
