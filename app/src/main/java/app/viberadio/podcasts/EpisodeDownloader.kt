package app.viberadio.podcasts

import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Keeps the next few queued episodes on the device and nothing else.
 *
 * Only what [sync] is given is downloaded, and the repository only ever passes unplayed
 * episodes, so marking an episode played before it comes up means it is never fetched.
 * Anything not in the target list (played, removed, or pushed back) is deleted.
 */
class EpisodeDownloader(
    private val dir: File,
    private val open: (String) -> InputStream = Http::open,
) {
    private val _downloaded = MutableStateFlow(scan())

    /** IDs of episodes whose audio is fully on the device. */
    val downloaded: StateFlow<Set<String>> = _downloaded.asStateFlow()

    fun file(id: String): File = File(dir, "$id$SUFFIX")

    /**
     * Blocking. Deletes everything outside [targets] and [alsoKeep] (files kept but never fetched),
     * then fetches missing targets in order, stopping at the first one that would start with the
     * targets' files already at [maxBytes] or more (one episode can run past it), or with less than
     * [minFreeBytes] left on the device.
     */
    fun sync(targets: List<Episode>, alsoKeep: Set<String> = emptySet(), maxBytes: Long? = null, minFreeBytes: Long = MIN_FREE_BYTES) {
        dir.mkdirs()
        val keep = targets.mapTo(HashSet(alsoKeep)) { it.id }
        dir.listFiles()?.forEach { f ->
            val partial = f.name.endsWith(PARTIAL)
            if (partial || f.name.removeSuffix(SUFFIX) !in keep) f.delete()
        }
        _downloaded.value = scan()

        var used = 0L
        for (episode in targets) {
            val target = file(episode.id)
            if (target.exists()) {
                used += target.length()
                continue
            }
            if (maxBytes != null && used >= maxBytes) break
            if (dir.usableSpace < minFreeBytes) break
            val part = File(dir, "${episode.id}$PARTIAL")
            try {
                open(episode.audioUrl).use { input -> part.outputStream().use { input.copyTo(it) } }
                if (!part.renameTo(target)) throw IOException("Couldn't move ${part.name}")
            } catch (e: IOException) {
                // Leave it for the next sync; playback streams it meanwhile.
                part.delete()
                continue
            }
            used += target.length()
            _downloaded.value = scan()
        }
    }

    private fun scan(): Set<String> =
        dir.listFiles { f -> f.name.endsWith(SUFFIX) }?.mapTo(HashSet()) { it.name.removeSuffix(SUFFIX) }.orEmpty()

    private companion object {
        const val SUFFIX = ".audio"
        const val PARTIAL = ".part"

        /** Left free on the device, so a big preload can't fill it. */
        const val MIN_FREE_BYTES = 1L shl 30
    }
}
