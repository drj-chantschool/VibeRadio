package app.viberadio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.viberadio.R
import app.viberadio.feedback.Thumb
import app.viberadio.ui.theme.VibeRadioTheme

@Composable
fun RadioScreen(
    state: RadioUiState,
    onRequestPermission: () -> Unit,
    onTune: (Int) -> Unit,
    onPlay: () -> Unit,
    onThumb: (Thumb) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 32.dp, vertical = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (state) {
                is RadioUiState.NeedsPermission -> PermissionPrompt(state.denied, onRequestPermission)
                RadioUiState.Scanning -> Note(stringResource(R.string.scanning))
                RadioUiState.Empty -> Note(stringResource(R.string.library_empty))
                is RadioUiState.Ready -> Tuner(state, onTune, onPlay, onThumb)
            }
        }
    }
}

@Composable
private fun Tuner(state: RadioUiState.Ready, onTune: (Int) -> Unit, onPlay: () -> Unit, onThumb: (Thumb) -> Unit) {
    val tuned = state.tunedIndex?.let(state.channelNames::getOrNull)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = tuned ?: stringResource(R.string.idle),
            style = if (tuned != null) MaterialTheme.typography.displaySmall else MaterialTheme.typography.titleMedium,
            color = if (tuned != null) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(40.dp))
        ChannelKnob(
            channelNames = state.channelNames,
            selected = state.tunedIndex,
            onSelect = onTune,
            showPlay = !state.playing,
            onPlay = onPlay,
            modifier = Modifier.widthIn(max = 280.dp).fillMaxWidth(),
        )
        Spacer(Modifier.height(40.dp))
        // Height reserved so the layout doesn't jump when metadata arrives.
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.height(48.dp)) {
            state.nowPlaying?.let { now ->
                Text(
                    text = now.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                now.artist?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        // Height reserved so the layout stays put while a podcast episode plays (no thumbs).
        Box(contentAlignment = Alignment.Center, modifier = Modifier.height(48.dp)) {
            state.feedback?.let { Thumbs(it, onThumb) }
        }
    }
}

/**
 * Thumbs up / down for the playing song (#13). Feedback only: neither skips. The last thumb
 * pressed for this song is drawn darker as a quiet "noted"; everything else stays faint.
 */
@Composable
private fun Thumbs(feedback: SongFeedback, onThumb: (Thumb) -> Unit) {
    val haptics = LocalHapticFeedback.current
    Row(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
        for (thumb in Thumb.entries) {
            val pressed = feedback.lastPress == thumb
            IconButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    onThumb(thumb)
                },
            ) {
                Icon(
                    imageVector = thumb.icon,
                    contentDescription = stringResource(if (thumb == Thumb.UP) R.string.thumb_up else R.string.thumb_down),
                    tint = if (pressed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

private val Thumb.icon: ImageVector
    get() = when (this) {
        Thumb.UP -> ThumbIcons.Up
        Thumb.DOWN -> ThumbIcons.Down
    }

@Composable
private fun PermissionPrompt(denied: Boolean, onRequest: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Note(stringResource(if (denied) R.string.permission_denied else R.string.permission_rationale))
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onRequest) { Text(stringResource(R.string.permission_grant)) }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

private val previewState = RadioUiState.Ready(
    channelNames = listOf("Jazz", "Rock", "Classical", "Folk", "Everything else"),
    tunedIndex = 2,
    nowPlaying = NowPlaying("Goldberg Variations: Aria", "Glenn Gould"),
    feedback = SongFeedback(lastPress = Thumb.UP),
    playing = true,
)

@Preview(name = "Light", showBackground = true)
@Composable
private fun RadioScreenLightPreview() {
    VibeRadioTheme(darkTheme = false) { RadioScreen(previewState, {}, {}, {}, {}) }
}

@Preview(name = "Paused", showBackground = true)
@Composable
private fun RadioScreenPausedPreview() {
    VibeRadioTheme(darkTheme = false) { RadioScreen(previewState.copy(playing = false), {}, {}, {}, {}) }
}

@Preview(name = "Dark", showBackground = true)
@Composable
private fun RadioScreenDarkPreview() {
    VibeRadioTheme(darkTheme = true) { RadioScreen(previewState, {}, {}, {}, {}) }
}
