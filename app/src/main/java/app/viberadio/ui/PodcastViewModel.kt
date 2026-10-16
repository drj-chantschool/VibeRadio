package app.viberadio.ui

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.viberadio.R
import app.viberadio.VibeRadioApp
import app.viberadio.dial.ChannelLineup
import app.viberadio.dial.ChannelNamesRepository
import app.viberadio.podcasts.FeedParseException
import app.viberadio.podcasts.PodcastLibrary
import app.viberadio.podcasts.PodcastRepository
import app.viberadio.podcasts.PodcastRepository.SubscribeResult
import app.viberadio.podcasts.PodcastPriority
import app.viberadio.podcasts.PodcastSpeeds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PodcastUiState(
    val loaded: Boolean = false,
    val upNext: List<EpisodeUi> = emptyList(),
    val shows: List<ShowUi> = emptyList(),
    val add: AddFeed = AddFeed(),
    /** What the podcast-only channel is called on the dial (The Voice, unless renamed). */
    val voiceName: String = "",
)

data class ShowUi(
    val feedUrl: String,
    val title: String,
    /** Every named channel, checked where the show plays on it (#18). */
    val channels: List<ChannelChoiceUi>,
    /** "Pause music": the show's episodes play by themselves, with the music paused (#2). */
    val pauseMusic: Boolean,
    val unplayed: Int,
    /** Playback speed for all of this show's episodes, one of [PodcastSpeeds.STEPS]. */
    val speed: Float,
    /** Queue priority, lower plays first; within [PodcastPriority.MIN]..[PodcastPriority.MAX]. */
    val priority: Int,
    /** Newest first. */
    val episodes: List<EpisodeUi>,
)

/** One channel a show can be put on. */
data class ChannelChoiceUi(val id: String, val name: String, val checked: Boolean)

data class EpisodeUi(
    val id: String,
    val title: String,
    val showTitle: String,
    /** Epoch millis, 0 if unknown. */
    val publishedMs: Long,
    val played: Boolean,
    val downloaded: Boolean,
    val started: Boolean,
)

data class AddFeed(
    val draft: String = "",
    val adding: Boolean = false,
    @StringRes val error: Int? = null,
)

/**
 * The podcast page (#20): add feeds, choose which channels each show plays on and whether it
 * pauses the music, set each show's playback speed and queue priority, and mark episodes played (singly, or a whole back catalogue) before they
 * are ever downloaded.
 */
class PodcastViewModel(application: Application) : AndroidViewModel(application) {

    private val podcasts: PodcastRepository = (application as VibeRadioApp).container.podcastRepository
    private val names: ChannelNamesRepository = (application as VibeRadioApp).container.channelNames
    private val add = MutableStateFlow(AddFeed())

    val uiState: StateFlow<PodcastUiState> =
        combine(podcasts.loaded, podcasts.library, podcasts.downloaded, add, names.names) { loaded, library, downloaded, addFeed, channelNames ->
            PodcastUiState(
                loaded = loaded,
                upNext = library.upNext(UP_NEXT_COUNT).map { library.ui(it.id, downloaded) },
                shows = library.podcasts.map { show ->
                    val episodes = library.episodesOf(show.feedUrl).map { library.ui(it.id, downloaded) }
                    ShowUi(
                        feedUrl = show.feedUrl,
                        title = show.title,
                        channels = ChannelLineup.ids.map { id ->
                            ChannelChoiceUi(id, channelNames.of(id).orEmpty(), checked = id in show.channels)
                        },
                        pauseMusic = show.pauseMusic,
                        unplayed = episodes.count { !it.played },
                        speed = show.speed,
                        priority = show.priority,
                        episodes = episodes,
                    )
                },
                add = addFeed,
                voiceName = channelNames.of(ChannelLineup.VOICE).orEmpty(),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PodcastUiState())

    fun onDraftChange(text: String) {
        add.value = add.value.copy(draft = text, error = null)
    }

    fun addFeed() {
        val draft = add.value.draft
        if (draft.isBlank() || add.value.adding) return
        add.value = add.value.copy(adding = true, error = null)
        viewModelScope.launch {
            add.value = when (val result = podcasts.subscribe(draft)) {
                is SubscribeResult.Added -> AddFeed()
                is SubscribeResult.AlreadySubscribed -> AddFeed(draft = draft, error = R.string.add_feed_already)
                SubscribeResult.InvalidUrl -> AddFeed(draft = draft, error = R.string.add_feed_invalid)
                is SubscribeResult.Failed -> AddFeed(
                    draft = draft,
                    error = if (result.error is FeedParseException) R.string.add_feed_not_a_feed else R.string.add_feed_unreachable,
                )
            }
        }
    }

    fun setChannel(feedUrl: String, channelId: String, on: Boolean) = podcasts.setChannel(feedUrl, channelId, on)

    fun setPauseMusic(feedUrl: String, on: Boolean) = podcasts.setPauseMusic(feedUrl, on)

    fun setSpeed(feedUrl: String, speed: Float) = podcasts.setSpeed(feedUrl, speed)

    fun setPriority(feedUrl: String, priority: Int) = podcasts.setPriority(feedUrl, priority)

    fun togglePlayed(episode: EpisodeUi) = podcasts.setPlayed(episode.id, !episode.played)

    fun markOlderPlayed(episode: EpisodeUi) = podcasts.markOlderPlayed(episode.id)

    fun remove(feedUrl: String) = podcasts.unsubscribe(feedUrl)

    /** Checks feeds at most hourly, when the app comes to the front. */
    fun onResume() {
        viewModelScope.launch { podcasts.refresh(maxAgeMs = REFRESH_AFTER_MS) }
    }

    private fun PodcastLibrary.ui(id: String, downloaded: Set<String>): EpisodeUi {
        val episode = episode(id)!!
        return EpisodeUi(
            id = id,
            title = episode.title,
            showTitle = podcast(episode.feedUrl)?.title.orEmpty(),
            publishedMs = episode.publishedMs,
            played = isPlayed(id),
            downloaded = id in downloaded,
            started = (positions[id] ?: 0L) > 0L,
        )
    }

    private companion object {
        const val UP_NEXT_COUNT = 5
        const val REFRESH_AFTER_MS = 60 * 60 * 1000L
    }
}
