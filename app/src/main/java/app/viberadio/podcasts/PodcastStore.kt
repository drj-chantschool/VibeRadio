package app.viberadio.podcasts

import android.util.AtomicFile
import java.io.File
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

/** Where the [PodcastLibrary] survives restarts. Blocking; call off the main thread. */
interface PodcastStore {
    /** The saved library, or null if nothing has been saved yet. */
    fun read(): PodcastLibrary?
    fun write(library: PodcastLibrary)
}

/**
 * One JSON file, replaced atomically on every write. The library is small (feeds, episode
 * metadata, played IDs), so rewriting it whole keeps this trivial.
 */
class JsonPodcastStore(file: File) : PodcastStore {
    private val atomic = AtomicFile(file)

    override fun read(): PodcastLibrary? {
        val text = try {
            atomic.readFully().decodeToString()
        } catch (e: IOException) {
            return null
        }
        return runCatching { decode(JSONObject(text)) }.getOrNull()
    }

    override fun write(library: PodcastLibrary) {
        val bytes = encode(library).toString().encodeToByteArray()
        val out = atomic.startWrite()
        try {
            out.write(bytes)
            atomic.finishWrite(out)
        } catch (e: IOException) {
            atomic.failWrite(out)
            throw e
        }
    }

    private fun encode(library: PodcastLibrary) = JSONObject().apply {
        put("version", VERSION)
        put("podcasts", JSONArray().apply {
            library.podcasts.forEach { p ->
                put(JSONObject().apply {
                    put("feedUrl", p.feedUrl)
                    put("title", p.title)
                    put("channels", JSONArray(p.channels.toList()))
                    put("lastRefreshedMs", p.lastRefreshedMs)
                    put("speed", p.speed.toDouble())
                    put("pauseMusic", p.pauseMusic)
                    put("priority", p.priority)
                    put("skipIntroSeconds", p.skipIntroSeconds)
                })
            }
        })
        put("episodes", JSONArray().apply {
            library.episodes.forEach { e ->
                put(JSONObject().apply {
                    put("id", e.id)
                    put("feedUrl", e.feedUrl)
                    put("title", e.title)
                    put("publishedMs", e.publishedMs)
                    put("audioUrl", e.audioUrl)
                    e.durationMs?.let { put("durationMs", it) }
                })
            }
        })
        put("played", JSONArray(library.played.toList()))
        put("positions", JSONObject().apply { library.positions.forEach { (id, ms) -> put(id, ms) } })
    }

    private fun decode(json: JSONObject): PodcastLibrary {
        val podcasts = json.getJSONArray("podcasts").objects().map { p ->
            Podcast(
                feedUrl = p.getString("feedUrl"),
                title = p.getString("title"),
                channels = p.getJSONArray("channels").strings().toSet(),
                lastRefreshedMs = p.optLong("lastRefreshedMs"),
                // Absent in files written before per-show speed.
                speed = PodcastSpeeds.snap(p.optDouble("speed", PodcastSpeeds.DEFAULT.toDouble()).toFloat()),
                // Absent in files written before "Pause music".
                pauseMusic = p.optBoolean("pauseMusic", false),
                // Absent in files written before per-show priority.
                priority = PodcastPriority.clamp(p.optInt("priority", PodcastPriority.DEFAULT)),
                // Absent in files written before "Skip intro".
                skipIntroSeconds = PodcastSkipIntro.snap(p.optInt("skipIntroSeconds", PodcastSkipIntro.OFF)),
            )
        }
        val episodes = json.getJSONArray("episodes").objects().map { e ->
            Episode(
                id = e.getString("id"),
                feedUrl = e.getString("feedUrl"),
                title = e.getString("title"),
                publishedMs = e.optLong("publishedMs"),
                audioUrl = e.getString("audioUrl"),
                durationMs = if (e.has("durationMs")) e.getLong("durationMs") else null,
            )
        }
        val positions = json.optJSONObject("positions")
        return PodcastLibrary(
            podcasts = podcasts,
            episodes = episodes,
            played = json.optJSONArray("played")?.strings()?.toSet().orEmpty(),
            positions = positions?.keys()?.asSequence()?.associateWith { positions.getLong(it) }.orEmpty(),
        )
    }

    private fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }
    private fun JSONArray.strings(): List<String> = List(length()) { getString(it) }

    private companion object {
        const val VERSION = 1
    }
}
