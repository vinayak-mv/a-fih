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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.afih.desktop.Buttons
import app.afih.desktop.Ui
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
    Column(Modifier.fillMaxSize().background(Ui.BgDeep).focusRequester(focus).onPreviewKeyEvent {
        if (it.type != KeyEventType.KeyDown) false else when (it.key) {
            Key.Spacebar -> { session.togglePause(); true }
            Key.DirectionLeft -> { session.skip(-10); true }
            Key.DirectionRight -> { session.skip(10); true }
            Key.F -> { onFullscreen(); true }
            Key.Escape -> { if (fullscreen) onFullscreen() else if (!closing) { closing = true; onBack() }; true }
            else -> false
        }
    }.focusable()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Ui.Space.lg, vertical = Ui.Space.sm), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Ui.Space.md)) {
            TextButton(enabled = !closing, onClick = { closing = true; onBack() }, colors = ButtonDefaults.textButtonColors(contentColor = Ui.TextSecondary)) { Text(if (closing) "Closing…" else "Back") }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(session.media.title, color = Ui.TextPrimary, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(session.media.fileName, color = Ui.TextMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = onFullscreen, colors = ButtonDefaults.textButtonColors(contentColor = Ui.TextSecondary)) { Text(if (fullscreen) "Exit fullscreen" else "Fullscreen") }
        }
        key(session) {
            SwingPanel(
                factory = { JPanel(BorderLayout()).apply { background = java.awt.Color.BLACK; add(MpvVideoCanvas(session::attach, session::detach), BorderLayout.CENTER) } },
                background = Color.Black,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
        Surface(color = Ui.Surface, contentColor = Ui.TextPrimary, shape = RoundedCornerShape(topStart = Ui.Radius.panel, topEnd = Ui.Radius.panel), border = androidx.compose.foundation.BorderStroke(1.dp, Ui.Hairline)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = Ui.Space.xl, vertical = Ui.Space.md), verticalArrangement = Arrangement.spacedBy(Ui.Space.sm)) {
                if (state.loading || state.buffering) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Ui.Accent, trackColor = Ui.Raised)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Ui.Space.sm)) {
                    Text(when { state.failure != null -> state.failure!!; state.loading -> "Opening stream…"; state.buffering -> "Buffering…"; state.ended -> "Finished"; state.paused -> "Paused"; else -> "Playing" }, style = MaterialTheme.typography.bodySmall, color = if (state.failure != null) Ui.Negative else Ui.TextSecondary, modifier = Modifier.weight(1f))
                    if (state.failure != null) TextButton(onClick = session::retry, colors = Buttons.accentText) { Text("Retry") }
                    Text("${playbackClock(state.positionMs)} / ${playbackClock(state.durationMs)}", style = MaterialTheme.typography.labelMedium, color = Ui.TextMuted)
                }
                Slider(
                    value = dragging ?: if (state.durationMs > 0) (state.positionMs.toDouble() / state.durationMs).toFloat().coerceIn(0f, 1f) else 0f,
                    onValueChange = { dragging = it },
                    onValueChangeFinished = { dragging?.let { session.seek((it * state.durationMs).toLong()) }; dragging = null },
                    enabled = state.seekable && state.durationMs > 0 && !closing,
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(thumbColor = Ui.TextPrimary, activeTrackColor = Ui.Accent, inactiveTrackColor = Ui.Raised),
                )
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Ui.Space.sm), verticalArrangement = Arrangement.spacedBy(Ui.Space.xs), itemVerticalAlignment = Alignment.CenterVertically) {
                    Button(enabled = !state.loading && !closing, onClick = session::togglePause, colors = Buttons.primaryColors, contentPadding = Buttons.controlPadding) { Text(if (state.ended) "Replay" else if (state.paused) "Play" else "Pause") }
                    OutlinedButton(enabled = state.seekable, onClick = { session.skip(-10) }, colors = Buttons.secondaryColors, border = androidx.compose.foundation.BorderStroke(1.dp, Ui.HairlineStrong), contentPadding = Buttons.controlPadding) { Text("Back 10 sec") }
                    OutlinedButton(enabled = state.seekable, onClick = { session.skip(10) }, colors = Buttons.secondaryColors, border = androidx.compose.foundation.BorderStroke(1.dp, Ui.HairlineStrong), contentPadding = Buttons.controlPadding) { Text("Forward 10 sec") }
                    TrackMenu("Audio", state.audio, session::audio)
                    TrackMenu("Subtitles", state.subtitles, session::subtitles)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Ui.Space.xs)) {
                        Text("Volume", style = MaterialTheme.typography.labelMedium, color = Ui.TextSecondary)
                        Slider(value = state.volume.toFloat(), onValueChange = { session.volume(it.toInt()) }, valueRange = 0f..100f, modifier = Modifier.width(140.dp), colors = SliderDefaults.colors(thumbColor = Ui.TextPrimary, activeTrackColor = Ui.TextSecondary, inactiveTrackColor = Ui.Raised))
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
        OutlinedButton(enabled = tracks.isNotEmpty(), onClick = { expanded = true }, colors = Buttons.secondaryColors, border = androidx.compose.foundation.BorderStroke(1.dp, Ui.HairlineStrong), contentPadding = Buttons.controlPadding) { Text(label) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, containerColor = Ui.Surface) {
            tracks.forEach { track -> DropdownMenuItem(
                text = { Text((if (track.selected) "Selected · " else "") + track.label, maxLines = 2, color = Ui.TextPrimary) },
                onClick = { onSelect(track.id); expanded = false },
            ) }
        }
    }
}
