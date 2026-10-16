package app.viberadio.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.annotation.StringRes
import androidx.compose.ui.unit.dp
import app.viberadio.R
import app.viberadio.podcasts.PodcastPriority
import app.viberadio.podcasts.PodcastSpeeds
import app.viberadio.ui.theme.VibeRadioTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The podcast page (#20). More control than the song page, because podcasts are curated and
 * long-form: add a feed, choose the channels a show plays on and whether it pauses the music,
 * and mark episodes played before they come up. There is still no play button here; listening happens by
 * tuning the dial.
 */
@Composable
fun PodcastScreen(
    state: PodcastUiState,
    onDraftChange: (String) -> Unit,
    onAdd: () -> Unit,
    onSetChannel: (feedUrl: String, channelId: String, on: Boolean) -> Unit,
    onSetPauseMusic: (feedUrl: String, on: Boolean) -> Unit,
    onTogglePlayed: (EpisodeUi) -> Unit,
    onMarkOlderPlayed: (EpisodeUi) -> Unit,
    onSetSpeed: (feedUrl: String, speed: Float) -> Unit,
    onSetPriority: (feedUrl: String, priority: Int) -> Unit,
    onRemove: (feedUrl: String) -> Unit,
) {
    var expanded by rememberSaveable(stateSaver = ExpandedSaver) { mutableStateOf(setOf<String>()) }
    var confirmRemove by rememberSaveable { mutableStateOf<String?>(null) }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().safeDrawingPadding(),
            // Bottom room for the page dots.
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 56.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.podcasts_title),
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(20.dp))
                AddFeedRow(state.add, onDraftChange, onAdd)
            }

            if (state.loaded && state.shows.isNotEmpty()) {
                section(R.string.podcasts_up_next)
                if (state.upNext.isEmpty()) {
                    item { Faint(stringResource(R.string.podcasts_nothing_queued)) }
                } else {
                    items(state.upNext, key = { "next:" + it.id }) { UpNextRow(it) }
                }

                section(R.string.podcasts_shows)
                for (show in state.shows) {
                    val open = show.feedUrl in expanded
                    item(key = "show:" + show.feedUrl) {
                        ShowRow(
                            show = show,
                            open = open,
                            onToggleOpen = { expanded = if (open) expanded - show.feedUrl else expanded + show.feedUrl },
                        )
                    }
                    if (open) {
                        item(key = "channels:" + show.feedUrl) {
                            ChannelChoices(show.channels) { id, on -> onSetChannel(show.feedUrl, id, on) }
                        }
                        item(key = "pause:" + show.feedUrl) {
                            PauseMusicRow(show.pauseMusic) { onSetPauseMusic(show.feedUrl, it) }
                        }
                        item(key = "speed:" + show.feedUrl) {
                            SpeedRow(show.speed) { onSetSpeed(show.feedUrl, it) }
                        }
                        item(key = "priority:" + show.feedUrl) {
                            PriorityRow(show.priority) { onSetPriority(show.feedUrl, it) }
                        }
                        item(key = "hint:" + show.feedUrl) { Faint(stringResource(R.string.podcasts_mark_hint)) }
                        items(show.episodes, key = { "ep:" + it.id }) { episode ->
                            EpisodeRow(episode, onTogglePlayed, onMarkOlderPlayed)
                        }
                        item(key = "remove:" + show.feedUrl) {
                            TextButton(onClick = { confirmRemove = show.feedUrl }) {
                                Text(stringResource(R.string.podcasts_remove_show))
                            }
                        }
                    }
                    item(key = "rule:" + show.feedUrl) { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
                }
            } else if (state.loaded) {
                item {
                    Spacer(Modifier.height(32.dp))
                    Faint(stringResource(R.string.podcasts_empty, state.voiceName))
                }
            }
        }
    }

    confirmRemove?.let { feedUrl ->
        val title = state.shows.firstOrNull { it.feedUrl == feedUrl }?.title.orEmpty()
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text(stringResource(R.string.podcasts_remove_title, title)) },
            text = { Text(stringResource(R.string.podcasts_remove_body)) },
            confirmButton = {
                TextButton(onClick = {
                    onRemove(feedUrl)
                    expanded = expanded - feedUrl
                    confirmRemove = null
                }) { Text(stringResource(R.string.podcasts_remove_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = null }) { Text(stringResource(R.string.cancel)) }
            },
            containerColor = MaterialTheme.colorScheme.background,
        )
    }
}

@Composable
private fun AddFeedRow(add: AddFeed, onDraftChange: (String) -> Unit, onAdd: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = add.draft,
            onValueChange = onDraftChange,
            placeholder = { Text(stringResource(R.string.add_feed_hint)) },
            singleLine = true,
            enabled = !add.adding,
            isError = add.error != null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onAdd() }),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onAdd, enabled = !add.adding && add.draft.isNotBlank()) {
            Text(stringResource(if (add.adding) R.string.add_feed_adding else R.string.add_feed))
        }
    }
    add.error?.let {
        Spacer(Modifier.height(6.dp))
        Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
}

private fun LazyListScope.section(@StringRes title: Int) {
    item(key = "section:$title") {
        Spacer(Modifier.height(28.dp))
        Text(
            stringResource(title).uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun UpNextRow(episode: EpisodeUi) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            episode.title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            listOfNotNull(
                episode.showTitle,
                stringResource(
                    when {
                        episode.started -> R.string.episode_resumes
                        episode.downloaded -> R.string.episode_on_device
                        else -> R.string.episode_streams
                    },
                ),
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ShowRow(show: ShowUi, open: Boolean, onToggleOpen: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = stringResource(if (open) R.string.podcasts_hide_episodes else R.string.podcasts_show_episodes)) {
                onToggleOpen()
            }
            .padding(vertical = 12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                show.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    stringResource(R.string.podcasts_unplayed, show.unplayed),
                    formatSpeed(show.speed).takeIf { show.speed != PodcastSpeeds.DEFAULT },
                    stringResource(R.string.podcasts_priority_summary, show.priority).takeIf { show.priority != PodcastPriority.DEFAULT },
                    stringResource(R.string.podcasts_pause_music).takeIf { show.pauseMusic },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
            )
            val on = show.channels.filter { it.checked }.map { it.name }
            Text(
                if (on.isEmpty()) stringResource(R.string.podcasts_off_air) else on.joinToString(", "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            if (open) "\u2212" else "+",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

/** A checkbox per named channel: the channels this show plays on (#18). */
@Composable
private fun ChannelChoices(channels: List<ChannelChoiceUi>, onSet: (id: String, on: Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            stringResource(R.string.podcasts_channels),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        for (channel in channels) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(value = channel.checked, role = Role.Checkbox) { onSet(channel.id, it) },
            ) {
                // The row handles the tap, so the box itself isn't a separate target.
                Checkbox(checked = channel.checked, onCheckedChange = null)
                Text(
                    channel.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

/** "Pause music": the music stops while this show plays, and picks up after (#2). */
@Composable
private fun PauseMusicRow(on: Boolean, onSet: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = on, role = Role.Switch, onValueChange = onSet)
            .padding(vertical = 8.dp),
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(
                stringResource(R.string.podcasts_pause_music),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                stringResource(R.string.podcasts_pause_music_about),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        // The row handles the toggle, so the switch itself is display-only.
        Switch(checked = on, onCheckedChange = null)
    }
}

/**
 * Per-show speed (applies to every episode, on every channel). A stepper over a short fixed
 * ladder, not a slider: set it once and forget it.
 */
@Composable
private fun SpeedRow(speed: Float, onSetSpeed: (Float) -> Unit) {
    val slower = stringResource(R.string.podcasts_speed_slower)
    val faster = stringResource(R.string.podcasts_speed_faster)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            stringResource(R.string.podcasts_speed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = { onSetSpeed(PodcastSpeeds.slower(speed)) },
            enabled = speed > PodcastSpeeds.STEPS.first(),
            modifier = Modifier.semantics { contentDescription = slower },
        ) { Text("\u2212") }
        Text(
            formatSpeed(speed),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(64.dp),
        )
        TextButton(
            onClick = { onSetSpeed(PodcastSpeeds.faster(speed)) },
            enabled = speed < PodcastSpeeds.STEPS.last(),
            modifier = Modifier.semantics { contentDescription = faster },
        ) { Text("+") }
    }
}

/**
 * Per-show queue priority: lower plays first, before the usual started-then-oldest order.
 * A stepper like speed, so it stays a set-once choice.
 */
@Composable
private fun PriorityRow(priority: Int, onSetPriority: (Int) -> Unit) {
    val lower = stringResource(R.string.podcasts_priority_lower)
    val higher = stringResource(R.string.podcasts_priority_higher)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(
                stringResource(R.string.podcasts_priority),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                stringResource(R.string.podcasts_priority_about),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        TextButton(
            onClick = { onSetPriority(priority - 1) },
            enabled = priority > PodcastPriority.MIN,
            modifier = Modifier.semantics { contentDescription = lower },
        ) { Text("\u2212") }
        Text(
            priority.toString(),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(48.dp),
        )
        TextButton(
            onClick = { onSetPriority(priority + 1) },
            enabled = priority < PodcastPriority.MAX,
            modifier = Modifier.semantics { contentDescription = higher },
        ) { Text("+") }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeRow(episode: EpisodeUi, onTogglePlayed: (EpisodeUi) -> Unit, onMarkOlderPlayed: (EpisodeUi) -> Unit) {
    val ink = if (episode.played) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onBackground
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                role = Role.Checkbox,
                onClickLabel = stringResource(if (episode.played) R.string.episode_mark_unplayed else R.string.episode_mark_played),
                onLongClickLabel = stringResource(R.string.episode_mark_older_played),
                onLongClick = { onMarkOlderPlayed(episode) },
                onClick = { onTogglePlayed(episode) },
            )
            .padding(vertical = 2.dp),
    ) {
        // The row handles the tap, so the box itself isn't a separate target.
        Checkbox(checked = episode.played, onCheckedChange = null)
        Column(Modifier.weight(1f)) {
            Text(episode.title, style = MaterialTheme.typography.bodyMedium, color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val detail = listOfNotNull(
                formatDate(episode.publishedMs),
                when {
                    episode.played -> null
                    episode.started -> stringResource(R.string.episode_started)
                    episode.downloaded -> stringResource(R.string.episode_on_device)
                    else -> null
                },
            ).joinToString(" · ")
            if (detail.isNotEmpty()) {
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable
private fun Faint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 6.dp),
    )
}

/** Which shows are expanded; a Set isn't saveable on its own. */
private val ExpandedSaver = listSaver<Set<String>, String>(save = { it.toList() }, restore = { it.toSet() })

/** "1×", "1.25×", "0.8×": no trailing zeros. */
private fun formatSpeed(speed: Float): String =
    java.math.BigDecimal(speed.toString()).stripTrailingZeros().toPlainString() + "\u00d7"

private val dateFormat: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

private fun formatDate(epochMs: Long): String? =
    if (epochMs <= 0) null else Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate().format(dateFormat)

private val previewEpisodes = listOf(
    EpisodeUi("1", "The long way round", "Slow Radio", 1_791_000_000_000, played = false, downloaded = true, started = true),
    EpisodeUi("2", "Harbour sounds at dawn", "Slow Radio", 1_790_000_000_000, played = false, downloaded = true, started = false),
    EpisodeUi("3", "Pilot", "Slow Radio", 1_700_000_000_000, played = true, downloaded = false, started = false),
)

private fun previewChannels(vararg on: String) = listOf(
    "breeze" to "The Breeze", "buzz" to "The Buzz", "podcasts" to "The Voice",
).map { (id, name) -> ChannelChoiceUi(id, name, checked = id in on) }

private val previewState = PodcastUiState(
    loaded = true,
    upNext = previewEpisodes.take(2),
    shows = listOf(
        ShowUi("a", "Slow Radio", previewChannels("breeze", "podcasts"), pauseMusic = false, unplayed = 2, speed = 1.0f, priority = 0, episodes = previewEpisodes),
        ShowUi("b", "Field Notes", previewChannels(), pauseMusic = true, unplayed = 14, speed = 1.5f, priority = 1, episodes = emptyList()),
    ),
)

@Preview(name = "Light", showBackground = true)
@Composable
private fun PodcastScreenLightPreview() {
    VibeRadioTheme(darkTheme = false) { PodcastScreen(previewState, {}, {}, { _, _, _ -> }, { _, _ -> }, {}, {}, { _, _ -> }, { _, _ -> }, {}) }
}

@Preview(name = "Dark", showBackground = true)
@Composable
private fun PodcastScreenDarkPreview() {
    VibeRadioTheme(darkTheme = true) { PodcastScreen(previewState, {}, {}, { _, _, _ -> }, { _, _ -> }, {}, {}, { _, _ -> }, { _, _ -> }, {}) }
}
