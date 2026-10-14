package app.viberadio

import android.content.Context
import app.viberadio.channels.ChannelGenerator
import app.viberadio.channels.ChannelRepository
import app.viberadio.dial.ChannelLineup
import app.viberadio.dial.ChannelNamesRepository
import app.viberadio.dial.PrefsChannelNameStore
import app.viberadio.feedback.FeedbackDatabase
import app.viberadio.feedback.FeedbackRecorder
import app.viberadio.feedback.PlayHistory
import app.viberadio.library.MediaStoreLibraryScanner
import app.viberadio.playback.LiveRadio
import app.viberadio.playback.RecencyWeightedStreamPlanner
import app.viberadio.podcasts.EpisodeDownloader
import app.viberadio.podcasts.HttpFeedFetcher
import app.viberadio.podcasts.JsonPodcastStore
import app.viberadio.podcasts.PodcastRepository
import app.viberadio.ui.LineupStrings
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.io.FileNotFoundException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import app.viberadio.playback.StreamPlanner
import app.viberadio.playback.audio.AudioProcessorProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Manual dependency wiring, shared by the UI and the playback service (same process).
 * Swap implementations here when the issues land; nothing else should construct these.
 */
class AppContainer(context: Context) {

    /** Lives as long as the process: feed refreshes, downloads and saves outlast any screen. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val channelRepository = ChannelRepository(
        scanner = MediaStoreLibraryScanner(context.contentResolver),
        generator = ChannelGenerator(
            // Generated channels fill the five mood slots (The Breeze … The Hall) in order.
            maxChannels = ChannelLineup.moods.size,
            slotIds = ChannelLineup.moods,
            labels = ChannelGenerator.Labels(
                all = context.getString(R.string.channel_all),
                other = context.getString(R.string.channel_other),
                allSongs = context.getString(R.string.channel_shuffle),
            ),
        ),
        ioDispatcher = Dispatchers.IO,
    )

    /** What the named channels are called: the defaults, or the user's names from settings. */
    val channelNames = ChannelNamesRepository(
        defaults = LineupStrings.defaultNames(context),
        store = PrefsChannelNameStore(context),
        scope = appScope,
        ioDispatcher = Dispatchers.IO,
    )

    /** The podcast pipeline (#17): hand-assigned shows, one global queue. Independent of [channelRepository]. */
    val podcastRepository = PodcastRepository(
        store = JsonPodcastStore(File(context.filesDir, "podcasts.json")),
        fetcher = HttpFeedFetcher(),
        // App-specific storage: no permission needed, removed with the app.
        downloader = EpisodeDownloader(File(context.getExternalFilesDir(null) ?: context.filesDir, "episodes")),
        scope = appScope,
        ioDispatcher = Dispatchers.IO,
    )

    /**
     * Per-song state keyed on each file's content hash, so it survives moves, renames and rescans.
     * In internal storage, so it's included in backups. Imports the tab-separated feedback.log
     * from earlier builds on first open.
     */
    private val feedbackDatabase = FeedbackDatabase(context, legacyFile = File(context.filesDir, "feedback.log"))

    private val openTrack: (String) -> InputStream =
        { uri -> context.contentResolver.openInputStream(Uri.parse(uri)) ?: throw FileNotFoundException(uri) }

    /** Thumbs feedback on songs (#13): an append-only log. */
    val feedback = FeedbackRecorder(
        log = feedbackDatabase.feedbackLog,
        open = openTrack,
        ioDispatcher = Dispatchers.IO,
    )

    /** When each song last played; the shuffle weights by it. Loaded off the main thread at startup. */
    val playHistory = PlayHistory(
        store = feedbackDatabase.playHistory,
        hashStore = feedbackDatabase.trackHashes,
        open = openTrack,
        ioDispatcher = Dispatchers.IO,
    ).also { history -> appScope.launch(Dispatchers.IO) { history.load() } }

    /** What each song channel last aired, so tuning back in can join a song still on air. Loaded at startup. */
    val liveRadio = LiveRadio(
        store = feedbackDatabase.channelAirings,
        hashOf = playHistory::hashOf,
        ioDispatcher = Dispatchers.IO,
    ).also { radio -> appScope.launch(Dispatchers.IO) { radio.load() } }

    /** #2 and #4 replace this. */
    val streamPlanner: StreamPlanner = RecencyWeightedStreamPlanner(lastPlayedMs = playHistory::lastPlayedMs)

    /** #1, #5 and #6 add processors here. */
    val audioProcessors: AudioProcessorProvider = AudioProcessorProvider.None
}
