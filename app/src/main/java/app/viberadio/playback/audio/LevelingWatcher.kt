package app.viberadio.playback.audio

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline

/**
 * Asks [Leveling] for a level for everything a player has queued (#1), whenever its queue or
 * current item changes. The current item is urgent; the queued ones (the next song is queued a
 * whole song ahead) are measured in order behind it.
 */
class LevelingWatcher(private val leveling: Leveling) {

    private val listeners = mutableMapOf<Player, Player.Listener>()

    fun attach(player: Player) {
        val listener = object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) = requestAll(player)

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = requestAll(player)
        }
        player.addListener(listener)
        listeners[player] = listener
        requestAll(player)
    }

    fun detach() {
        listeners.forEach { (player, listener) -> player.removeListener(listener) }
        listeners.clear()
    }

    private fun requestAll(player: Player) {
        val current = player.currentMediaItemIndex
        val urgent = mutableListOf<LevelRequest>()
        val later = mutableListOf<LevelRequest>()
        for (i in 0 until player.mediaItemCount) {
            val request = player.getMediaItemAt(i).levelRequest() ?: continue
            if (i == current) urgent += request else if (i > current) later += request
        }
        leveling.request(urgent, urgent = true)
        leveling.request(later)
    }

    private fun MediaItem.levelRequest(): LevelRequest? {
        val uri = localConfiguration?.uri?.toString() ?: return null
        if (mediaId.isEmpty()) return null
        return LevelRequest(mediaId, uri)
    }
}
