package app.viberadio

import android.content.Context
import app.viberadio.channels.ChannelGenerator
import app.viberadio.channels.ChannelRepository
import app.viberadio.library.MediaStoreLibraryScanner
import app.viberadio.playback.ShuffledStreamPlanner
import app.viberadio.podcasts.EpisodeDownloader
import app.viberadio.podcasts.HttpFeedFetcher
import app.viberadio.podcasts.JsonPodcastStore
import app.viberadio.podcasts.PodcastRepository
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import app.viberadio.playback.StreamPlanner
import app.viberadio.playback.audio.AudioProcessorProvider
import kotlinx.coroutines.Dispatchers

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
            labels = ChannelGenerator.Labels(
                all = context.getString(R.string.channel_all),
                other = context.getString(R.string.channel_other),
                allSongs = context.getString(R.string.channel_all_songs),
            ),
        ),
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

    /** #2 and #4 replace this. */
    val streamPlanner: StreamPlanner = ShuffledStreamPlanner()

    /** #1, #5 and #6 add processors here. */
    val audioProcessors: AudioProcessorProvider = AudioProcessorProvider.None
}
