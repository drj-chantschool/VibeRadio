package app.viberadio

import android.content.Context
import app.viberadio.channels.ChannelGenerator
import app.viberadio.channels.ChannelRepository
import app.viberadio.library.MediaStoreLibraryScanner
import app.viberadio.playback.ShuffledStreamPlanner
import app.viberadio.playback.StreamPlanner
import app.viberadio.playback.audio.AudioProcessorProvider
import kotlinx.coroutines.Dispatchers

/**
 * Manual dependency wiring, shared by the UI and the playback service (same process).
 * Swap implementations here when the issues land; nothing else should construct these.
 */
class AppContainer(context: Context) {

    val channelRepository = ChannelRepository(
        scanner = MediaStoreLibraryScanner(context.contentResolver),
        generator = ChannelGenerator(
            labels = ChannelGenerator.Labels(
                all = context.getString(R.string.channel_all),
                other = context.getString(R.string.channel_other),
            ),
        ),
        ioDispatcher = Dispatchers.IO,
    )

    /** #2 and #4 replace this. */
    val streamPlanner: StreamPlanner = ShuffledStreamPlanner()

    /** #1, #5 and #6 add processors here. */
    val audioProcessors: AudioProcessorProvider = AudioProcessorProvider.None
}
