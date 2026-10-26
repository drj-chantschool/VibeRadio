package app.viberadio.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.viberadio.R
import app.viberadio.ui.theme.VibeRadioTheme

/**
 * An episode's details, on a page of its own to the right of the podcast page: opened by a long
 * press in Up next or History, gone once the pager is back on the podcast page. Read-only, like
 * the rest of the podcast side: nothing here plays.
 */
@Composable
fun EpisodeScreen(episode: EpisodeDetailUi?) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        if (episode == null) return@Surface
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                // Top end clear of the settings gear, bottom clear of the page dots.
                .padding(start = 24.dp, end = 48.dp, top = 24.dp, bottom = 56.dp),
        ) {
            Text(
                episode.showTitle.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                episode.title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(8.dp))
            val facts = listOfNotNull(
                formatDate(episode.publishedMs),
                episode.durationMs?.takeIf { it > 0 }?.let(::formatClock),
                when {
                    episode.played -> stringResource(R.string.episode_played)
                    episode.positionMs > 0 -> stringResource(R.string.episode_resumes_at, formatClock(episode.positionMs))
                    else -> null
                },
                stringResource(if (episode.downloaded) R.string.episode_on_device else R.string.episode_streams).takeIf { !episode.played },
            ).joinToString(" · ")
            if (facts.isNotEmpty()) {
                Text(facts, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
            }
            episode.heardMs?.let {
                Text(
                    stringResource(R.string.episode_heard, formatHeard(it)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(
                episode.description ?: stringResource(R.string.episode_no_description),
                style = MaterialTheme.typography.bodyLarge,
                color = if (episode.description == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}

/** "54:07", "1:02:30". */
private fun formatClock(ms: Long): String {
    val seconds = ms / 1000
    val (h, m, s) = Triple(seconds / 3600, seconds / 60 % 60, seconds % 60)
    val mmss = "${m.toString().padStart(if (h > 0) 2 else 1, '0')}:${s.toString().padStart(2, '0')}"
    return if (h > 0) "$h:$mmss" else mmss
}

private val previewEpisode = EpisodeDetailUi(
    title = "The long way round",
    showTitle = "Slow Radio",
    publishedMs = 1_791_000_000_000,
    durationMs = 3_247_000,
    played = false,
    downloaded = true,
    positionMs = 754_000,
    heardMs = null,
    description = "A walk along the coast path from first light, with the harbour waking up below.\n\n• Recorded in October\n• No music",
)

@Preview(name = "Light", showBackground = true)
@Composable
private fun EpisodeScreenLightPreview() {
    VibeRadioTheme(darkTheme = false) { EpisodeScreen(previewEpisode) }
}

@Preview(name = "Dark", showBackground = true)
@Composable
private fun EpisodeScreenDarkPreview() {
    VibeRadioTheme(darkTheme = true) { EpisodeScreen(previewEpisode) }
}
