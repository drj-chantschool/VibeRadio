package app.viberadio.ui

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.viberadio.R
import app.viberadio.VibeRadioApp
import app.viberadio.podcasts.FeedParseException
import app.viberadio.podcasts.PodcastChannels
import app.viberadio.podcasts.PodcastLibrary
import app.viberadio.podcasts.PodcastRepository
import app.viberadio.podcasts.PodcastRepository.SubscribeResult
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
)

data class ShowUi(
    val feedUrl: String,
    val title: String,
    /** On the Podcasts channel (#18). */
    val onAir: Boolean,
    val unplayed: Int,
    /** Playback speed for all of this show's episodes, one of [PodcastSpeeds.STEPS]. */
    val speed: Float,
    /** Newest first. */
    val episodes: List<EpisodeUi>,
)

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
 * The podcast page (#20): add feeds, put shows on the Podcasts channel, set each show's
 * playback speed, and mark episodes played (singly, or a whole back catalogue) before they
 * are ever downloaded.
 */
class PodcastViewModel(application: Application) : AndroidViewModel(application) {

    private val podcasts: PodcastRepository = (application as VibeRadioApp).container.podcastRepository
    private val add = MutableStateFlow(AddFeed())

    val uiState: StateFlow<PodcastUiState> =
        combine(podcasts.loaded, podcasts.library, podcasts.downloaded, add) { loaded, library, downloaded, addFeed ->
            PodcastUiState(
                loaded = loaded,
                upNext = library.upNext(UP_NEXT_COUNT).map { library.ui(it.id, downloaded) },
                shows = library.podcasts.map { show ->
                    val episodes = library.episodesOf(show.feedUrl).map { library.ui(it.id, downloaded) }
                    ShowUi(
                        feedUrl = show.feedUrl,
                        title = show.title,
                        onAir = PodcastChannels.PODCASTS_ID in show.channels,
                        unplayed = episodes.count { !it.played },
                        speed = show.speed,
                        episodes = episodes,
                    )
                },
                add = addFeed,
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

    fun setOnAir(feedUrl: String, onAir: Boolean) = podcasts.setChannel(feedUrl, PodcastChannels.PODCASTS_ID, onAir)

    fun setSpeed(feedUrl: String, speed: Float) = podcasts.setSpeed(feedUrl, speed)

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
