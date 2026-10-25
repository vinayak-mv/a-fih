package app.afih.desktop.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.input.key.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.awt.BorderLayout
import java.awt.Canvas
import javax.swing.JPanel
import javax.swing.SwingUtilities

/** Only the video surface is native; navigation and every visible control belong to Compose. */
internal class MpvVideoCanvas(private val onAttach: (Long) -> Unit, private val onDetach: (Long) -> Unit) : Canvas() {
    private var nativeId: Long? = null
    init { background = java.awt.Color.BLACK; isFocusable = false }
    override fun addNotify() {
        super.addNotify()
        SwingUtilities.invokeLater {
            if (isDisplayable && nativeId == null) {
                val id = Pointer.nativeValue(Native.getComponentPointer(this))
                nativeId = id
                onAttach(id)
            }
        }
    }
    override fun removeNotify() {
        nativeId?.let(onDetach); nativeId = null
        super.removeNotify()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InAppPlayer(session: PlaybackSession, fullscreen: Boolean, onFullscreen: () -> Unit, onBack: () -> Unit) {
    val state by session.state.collectAsState()
    var closing by remember(session) { mutableStateOf(false) }
    var dragging by remember(session) { mutableStateOf<Float?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(session) { focus.requestFocus() }
    Column(Modifier.fillMaxSize().background(Color(0xFF111114)).focusRequester(focus).onPreviewKeyEvent {
        if (it.type != KeyEventType.KeyDown) false else when (it.key) {
            Key.Spacebar -> { session.togglePause(); true }
            Key.DirectionLeft -> { session.skip(-10); true }
            Key.DirectionRight -> { session.skip(10); true }
            Key.F -> { onFullscreen(); true }
            Key.Escape -> { if (fullscreen) onFullscreen() else if (!closing) { closing = true; onBack() }; true }
            else -> false
        }
    }.focusable()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            TextButton(enabled = !closing, onClick = { closing = true; onBack() }) { Text(if (closing) "Closing…" else "← Back", color = Color.White) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(session.media.title, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(session.media.fileName, color = Color(0xFFA9A9B3), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = onFullscreen) { Text(if (fullscreen) "Exit fullscreen" else "Fullscreen", color = Color.White) }
        }
        key(session) {
            SwingPanel(
                factory = { JPanel(BorderLayout()).apply { background = java.awt.Color.BLACK; add(MpvVideoCanvas(session::attach, session::detach), BorderLayout.CENTER) } },
                background = Color.Black,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
        Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (state.loading || state.buffering) LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(when { state.failure != null -> state.failure!!; state.loading -> "Opening stream…"; state.buffering -> "Buffering…"; state.ended -> "Finished"; state.paused -> "Paused"; else -> "Playing" }, style = MaterialTheme.typography.bodySmall, color = if (state.failure != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    if (state.failure != null) TextButton(onClick = session::retry) { Text("Retry") }
                    Text("${playbackClock(state.positionMs)} / ${playbackClock(state.durationMs)}", style = MaterialTheme.typography.labelMedium)
                }
                Slider(
                    value = dragging ?: if (state.durationMs > 0) (state.positionMs.toDouble() / state.durationMs).toFloat().coerceIn(0f, 1f) else 0f,
                    onValueChange = { dragging = it },
                    onValueChangeFinished = { dragging?.let { session.seek((it * state.durationMs).toLong()) }; dragging = null },
                    enabled = state.seekable && state.durationMs > 0 && !closing,
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    Button(enabled = !state.loading && !closing, onClick = session::togglePause) { Text(if (state.ended) "Replay" else if (state.paused) "Play" else "Pause") }
                    TextButton(enabled = state.seekable, onClick = { session.skip(-10) }) { Text("−10 sec") }
                    TextButton(enabled = state.seekable, onClick = { session.skip(10) }) { Text("+10 sec") }
                    TrackMenu("Audio", state.audio, session::audio)
                    TrackMenu("Subtitles", state.subtitles, session::subtitles)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Volume", style = MaterialTheme.typography.labelMedium)
                        Slider(value = state.volume.toFloat(), onValueChange = { session.volume(it.toInt()) }, valueRange = 0f..100f, modifier = Modifier.width(120.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackMenu(label: String, tracks: List<PlaybackTrack>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(enabled = tracks.isNotEmpty(), onClick = { expanded = true }) { Text(label) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            tracks.forEach { track -> DropdownMenuItem(
                text = { Text((if (track.selected) "✓ " else "") + track.label, maxLines = 2) },
                onClick = { onSelect(track.id); expanded = false },
            ) }
        }
    }
}

internal fun playbackClock(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60) else "%02d:%02d".format(seconds / 60, seconds % 60)
}
