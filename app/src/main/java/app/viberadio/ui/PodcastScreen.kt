package app.viberadio.ui

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
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
import app.viberadio.podcasts.PodcastSkipIntro
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
 * tuning the dial. Pulling down from the top checks every feed now. Below Up next, History lists what was on
 * air in the last 24 hours; a long press on an episode in either opens its details on a page of their own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastScreen(
    state: PodcastUiState,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    /** The show open, if any: one at a time, closed when the page is left. */
    openShow: String?,
    onOpenShow: (feedUrl: String?) -> Unit,
    /** Long press in Up next or History: the episode's details. */
    onOpenEpisode: (episodeId: String) -> Unit,
    onDraftChange: (String) -> Unit,
    onAdd: () -> Unit,
    onSetChannel: (feedUrl: String, channelId: String, on: Boolean) -> Unit,
    onSetPauseMusic: (feedUrl: String, on: Boolean) -> Unit,
    onTogglePlayed: (EpisodeUi) -> Unit,
    onMarkOlderPlayed: (EpisodeUi) -> Unit,
    onSetSpeed: (feedUrl: String, speed: Float) -> Unit,
    onSetPriority: (feedUrl: String, priority: Int) -> Unit,
    onSetSkipIntro: (feedUrl: String, seconds: Int) -> Unit,
    onRemove: (feedUrl: String) -> Unit,
) {
    var confirmRemove by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    // Closing the show open before can carry the one just opened off screen: bring it back.
    LaunchedEffect(openShow) {
        val index = showRowIndex(state, openShow ?: return@LaunchedEffect) ?: return@LaunchedEffect
        withFrameNanos { } // Let the list lay out the change first.
        val row = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
        if (row == null || row.offset < listState.layoutInfo.viewportStartOffset) listState.animateScrollToItem(index)
    }

    val pullState = rememberPullToRefreshState()

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = onRefresh,
            state = pullState,
            modifier = Modifier.fillMaxSize(),
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pullState,
                    isRefreshing = refreshing,
                    modifier = Modifier.align(Alignment.TopCenter).safeDrawingPadding(),
                    containerColor = MaterialTheme.colorScheme.background,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            },
        ) {
            LazyColumn(
                state = listState,
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
                        items(state.upNext, key = { "next:" + it.id }) { UpNextRow(it, onOpenEpisode) }
                    }

                    section(R.string.podcasts_history)
                    if (state.history.isEmpty()) {
                        item(key = "history:none") { Faint(stringResource(R.string.podcasts_history_empty)) }
                    } else {
                        items(state.history, key = { "heard:" + it.id }) { HistoryRow(it, onOpenEpisode) }
                    }

                    section(R.string.podcasts_shows)
                    for (show in state.shows) {
                        val open = show.feedUrl == openShow
                        item(key = "show:" + show.feedUrl) {
                            // Opening a show closes the one open before.
                            ShowRow(show = show, open = open, onToggleOpen = { onOpenShow(if (open) null else show.feedUrl) })
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
                            item(key = "skip:" + show.feedUrl) {
                                SkipIntroRow(show.skipIntroSeconds) { onSetSkipIntro(show.feedUrl, it) }
                            }
                            item(key = "hint:" + show.feedUrl) { Faint(stringResource(R.string.podcasts_mark_hint)) }
                            items(show.episodes, key = { "ep:" + it.id }) { episode ->
                                EpisodeRow(episode, onTogglePlayed, onMarkOlderPlayed)
                            }
                            item(key = "remove:" + show.feedUrl) {
                                if (show.local) {
                                    // It comes back with the next scan; the folder setting is what removes it.
                                    Faint(stringResource(R.string.podcasts_folder_show))
                                } else {
                                    TextButton(onClick = { confirmRemove = show.feedUrl }) {
                                        Text(stringResource(R.string.podcasts_remove_show))
                                    }
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
                    if (openShow == feedUrl) onOpenShow(null)
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

/**
 * Where [feedUrl]'s row sits in the list, with no show open above it. Mirrors the items
 * [PodcastScreen] emits: the heading, Up next (its title and rows, or the one "nothing queued"
 * line), History (likewise), the Shows title, then a row and a rule per show.
 */
private fun showRowIndex(state: PodcastUiState, feedUrl: String): Int? {
    if (!state.loaded) return null
    val position = state.shows.indexOfFirst { it.feedUrl == feedUrl }.takeIf { it >= 0 } ?: return null
    return 1 + 1 + maxOf(1, state.upNext.size) + 1 + maxOf(1, state.history.size) + 1 + 2 * position
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
private fun UpNextRow(episode: EpisodeUi, onOpenEpisode: (String) -> Unit) {
    val details = stringResource(R.string.episode_details)
    Column(Modifier.fillMaxWidth().opensDetails(details) { onOpenEpisode(episode.id) }.padding(vertical = 6.dp)) {
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

/** What was on air, and when: "Slow Radio · 2 hours ago · resumes". */
@Composable
private fun HistoryRow(episode: EpisodeUi, onOpenEpisode: (String) -> Unit) {
    val details = stringResource(R.string.episode_details)
    Column(Modifier.fillMaxWidth().opensDetails(details) { onOpenEpisode(episode.id) }.padding(vertical = 6.dp)) {
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
                episode.heardMs?.let { formatHeard(it) },
                when {
                    episode.played -> stringResource(R.string.episode_played)
                    episode.started -> stringResource(R.string.episode_resumes)
                    else -> null
                },
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Long press opens the episode's details. A tap does nothing: there's nothing to play here. */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.opensDetails(label: String, onLongClick: () -> Unit): Modifier =
    combinedClickable(onLongClickLabel = label, onLongClick = onLongClick, onClick = {})

/**
 * A show's heading, and the tap target that opens it: a faintly tinted band with a chevron, so
 * it reads as something to tap.
 */
@Composable
private fun ShowRow(show: ShowUi, open: Boolean, onToggleOpen: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(ShowRowShape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClickLabel = stringResource(if (open) R.string.podcasts_hide_episodes else R.string.podcasts_show_episodes)) {
                onToggleOpen()
            }
            .padding(horizontal = 12.dp, vertical = 12.dp),
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
                    stringResource(R.string.podcasts_skip_intro_summary, formatSkipIntro(show.skipIntroSeconds))
                        .takeIf { show.skipIntroSeconds != PodcastSkipIntro.OFF },
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
        Chevron(up = open, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A heavy down chevron (up while open), drawn rather than typed so its weight is ours. */
@Composable
private fun Chevron(up: Boolean, color: Color) {
    Canvas(Modifier.size(width = 16.dp, height = 10.dp)) {
        val stroke = 2.5.dp.toPx()
        val inset = stroke / 2
        val (edge, tip) = if (up) size.height - inset to inset else inset to size.height - inset
        val path = Path().apply {
            moveTo(inset, edge)
            lineTo(size.width / 2, tip)
            lineTo(size.width - inset, edge)
        }
        drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
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

/**
 * Per-show "Skip intro": a new episode starts this far in. A stepper over a fixed ladder, like
 * speed, since an intro runs the same length every episode.
 */
@Composable
private fun SkipIntroRow(seconds: Int, onSet: (Int) -> Unit) {
    val shorter = stringResource(R.string.podcasts_skip_intro_shorter)
    val longer = stringResource(R.string.podcasts_skip_intro_longer)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(
                stringResource(R.string.podcasts_skip_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                stringResource(R.string.podcasts_skip_intro_about),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        TextButton(
            onClick = { onSet(PodcastSkipIntro.shorter(seconds)) },
            enabled = seconds > PodcastSkipIntro.STEPS.first(),
            modifier = Modifier.semantics { contentDescription = shorter },
        ) { Text("\u2212") }
        Text(
            formatSkipIntro(seconds),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(64.dp),
        )
        TextButton(
            onClick = { onSet(PodcastSkipIntro.longer(seconds)) },
            enabled = seconds < PodcastSkipIntro.STEPS.last(),
            modifier = Modifier.semantics { contentDescription = longer },
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

private val ShowRowShape = RoundedCornerShape(6.dp)

/** "Off", "30 s", "1:30". */
@Composable
private fun formatSkipIntro(seconds: Int): String = when {
    seconds <= 0 -> stringResource(R.string.podcasts_skip_intro_off)
    seconds < 60 -> stringResource(R.string.podcasts_skip_intro_seconds, seconds)
    else -> "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

/** "1×", "1.25×", "0.8×": no trailing zeros. */
private fun formatSpeed(speed: Float): String =
    java.math.BigDecimal(speed.toString()).stripTrailingZeros().toPlainString() + "\u00d7"

/** "5 minutes ago", "2 hours ago". */
internal fun formatHeard(epochMs: Long): String =
    DateUtils.getRelativeTimeSpanString(epochMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

private val dateFormat: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

internal fun formatDate(epochMs: Long): String? =
    if (epochMs <= 0) null else Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate().format(dateFormat)

private val previewEpisodes = listOf(
    EpisodeUi("1", "The long way round", "Slow Radio", 1_791_000_000_000, played = false, downloaded = true, started = true),
    EpisodeUi("2", "Harbour sounds at dawn", "Slow Radio", 1_790_000_000_000, played = false, downloaded = true, started = false),
    EpisodeUi("3", "Pilot", "Slow Radio", 1_700_000_000_000, played = true, downloaded = false, started = false),
)

private val previewHistory = listOf(
    EpisodeUi("4", "Night ferry", "Slow Radio", 1_789_000_000_000, played = true, downloaded = true, started = false, heardMs = 1_792_000_000_000),
)

private fun previewChannels(vararg on: String) = listOf(
    "breeze" to "The Breeze", "buzz" to "The Buzz", "podcasts" to "The Voice",
).map { (id, name) -> ChannelChoiceUi(id, name, checked = id in on) }

private val previewState = PodcastUiState(
    loaded = true,
    upNext = previewEpisodes.take(2),
    history = previewHistory,
    shows = listOf(
        ShowUi("a", "Slow Radio", local = false, previewChannels("breeze", "podcasts"), pauseMusic = false, unplayed = 2, speed = 1.0f, priority = 0, skipIntroSeconds = 30, episodes = previewEpisodes),
        ShowUi("b", "Field Notes", local = false, previewChannels(), pauseMusic = true, unplayed = 14, speed = 1.5f, priority = 1, skipIntroSeconds = 0, episodes = emptyList()),
    ),
)

@Preview(name = "Light", showBackground = true)
@Composable
private fun PodcastScreenLightPreview() {
    VibeRadioTheme(darkTheme = false) { PodcastScreen(previewState, false, {}, "a", {}, {}, {}, {}, { _, _, _ -> }, { _, _ -> }, {}, {}, { _, _ -> }, { _, _ -> }, { _, _ -> }, {}) }
}

@Preview(name = "Dark", showBackground = true)
@Composable
private fun PodcastScreenDarkPreview() {
    VibeRadioTheme(darkTheme = true) { PodcastScreen(previewState, false, {}, "a", {}, {}, {}, {}, { _, _, _ -> }, { _, _ -> }, {}, {}, { _, _ -> }, { _, _ -> }, { _, _ -> }, {}) }
}
