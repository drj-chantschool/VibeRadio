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

    /** Blocking. Deletes everything outside [targets], then fetches missing targets in order. */
    fun sync(targets: List<Episode>) {
        dir.mkdirs()
        val keep = targets.mapTo(HashSet()) { it.id }
        dir.listFiles()?.forEach { f ->
            val partial = f.name.endsWith(PARTIAL)
            if (partial || f.name.removeSuffix(SUFFIX) !in keep) f.delete()
        }
        _downloaded.value = scan()

        for (episode in targets) {
            val target = file(episode.id)
            if (target.exists()) continue
            val part = File(dir, "${episode.id}$PARTIAL")
            try {
                open(episode.audioUrl).use { input -> part.outputStream().use { input.copyTo(it) } }
                if (!part.renameTo(target)) throw IOException("Couldn't move ${part.name}")
            } catch (e: IOException) {
                // Leave it for the next sync; playback streams it meanwhile.
                part.delete()
                continue
            }
            _downloaded.value = scan()
        }
    }

    private fun scan(): Set<String> =
        dir.listFiles { f -> f.name.endsWith(SUFFIX) }?.mapTo(HashSet()) { it.name.removeSuffix(SUFFIX) }.orEmpty()

    private companion object {
        const val SUFFIX = ".audio"
        const val PARTIAL = ".part"
    }
}
