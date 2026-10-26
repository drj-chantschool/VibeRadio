package app.viberadio.podcasts

import android.util.AtomicFile
import android.util.JsonWriter
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.io.OutputStreamWriter
import org.json.JSONArray
import org.json.JSONObject

/** Where the [PodcastLibrary] survives restarts. Blocking; call off the main thread. */
interface PodcastStore {
    /** The saved library, or null if nothing has been saved yet. */
    fun read(): PodcastLibrary?
    fun write(library: PodcastLibrary)
}

/**
 * Two JSON files, each replaced atomically. [catalogFile] holds the shows and episodes (with their
 * descriptions, the bulk of it) and is rewritten only when they change: a feed refresh, a show's
 * settings. [stateFile] holds what playback changes all the time (played, resume points, history),
 * so the 15 s position saves rewrite only that small file.
 *
 * Shows and episodes are compared by reference: [PodcastLibrary] changes that don't touch them
 * keep the same lists. Not thread-safe; the repository serialises writes.
 */
class JsonPodcastStore(catalogFile: File, stateFile: File) : PodcastStore {
    private val catalog = AtomicFile(catalogFile)
    private val state = AtomicFile(stateFile)

    /** What [catalog] holds now, so an unchanged catalog isn't rewritten. */
    private var savedPodcasts: List<Podcast>? = null
    private var savedEpisodes: List<Episode>? = null

    override fun read(): PodcastLibrary? {
        val catalogJson = catalog.readJson() ?: return null
        val library = runCatching { decodeCatalog(catalogJson) }.getOrNull() ?: return null
        // Files written before the split keep the state in the catalog.
        val stateJson = state.readJson() ?: catalogJson
        val withState = runCatching { decodeState(library, stateJson) }.getOrDefault(library)
        savedPodcasts = withState.podcasts
        savedEpisodes = withState.episodes
        return withState
    }

    override fun write(library: PodcastLibrary) {
        // State first: a catalog without its state would read as all unplayed.
        state.writeJson(encodeState(library))
        if (library.podcasts === savedPodcasts && library.episodes === savedEpisodes) return
        writeCatalog(library)
        savedPodcasts = library.podcasts
        savedEpisodes = library.episodes
    }

    private fun AtomicFile.readJson(): JSONObject? {
        val text = try {
            readFully().decodeToString()
        } catch (e: IOException) {
            return null
        }
        return runCatching { JSONObject(text) }.getOrNull()
    }

    private fun AtomicFile.writeJson(json: JSONObject) {
        val bytes = json.toString().encodeToByteArray()
        val out = startWrite()
        try {
            out.write(bytes)
            finishWrite(out)
        } catch (e: IOException) {
            failWrite(out)
            throw e
        }
    }

    /**
     * Streams the catalog into the file rather than building it as one JSON tree and string first:
     * with descriptions it runs to tens of MB, several times over in memory as a tree and a string.
     * Anything thrown part-way leaves the old file in place.
     */
    private fun writeCatalog(library: PodcastLibrary) {
        val out = catalog.startWrite()
        try {
            // Flushed, not closed: finishWrite syncs and closes the stream itself.
            val writer = JsonWriter(BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8)))
            writer.encodeCatalog(library)
            writer.flush()
            catalog.finishWrite(out)
        } catch (e: Throwable) {
            catalog.failWrite(out)
            throw e
        }
    }

    private fun JsonWriter.encodeCatalog(library: PodcastLibrary) {
        beginObject()
        name("version").value(VERSION.toLong())
        name("podcasts").beginArray()
        library.podcasts.forEach { p ->
            beginObject()
            name("feedUrl").value(p.feedUrl)
            name("title").value(p.title)
            name("channels").beginArray()
            p.channels.forEach { value(it) }
            endArray()
            name("lastRefreshedMs").value(p.lastRefreshedMs)
            name("speed").value(p.speed.toDouble())
            name("pauseMusic").value(p.pauseMusic)
            name("priority").value(p.priority.toLong())
            name("skipIntroSeconds").value(p.skipIntroSeconds.toLong())
            endObject()
        }
        endArray()
        name("episodes").beginArray()
        library.episodes.forEach { e ->
            beginObject()
            name("id").value(e.id)
            name("feedUrl").value(e.feedUrl)
            name("title").value(e.title)
            name("publishedMs").value(e.publishedMs)
            name("audioUrl").value(e.audioUrl)
            e.durationMs?.let { name("durationMs").value(it) }
            e.description?.let { name("description").value(it) }
            endObject()
        }
        endArray()
        endObject()
    }

    private fun encodeState(library: PodcastLibrary) = JSONObject().apply {
        put("version", VERSION)
        put("played", JSONArray(library.played.toList()))
        put("positions", JSONObject().apply { library.positions.forEach { (id, ms) -> put(id, ms) } })
        put("heard", JSONObject().apply { library.heard.forEach { (id, ms) -> put(id, ms) } })
    }

    private fun decodeCatalog(json: JSONObject): PodcastLibrary {
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
                // Absent in files written before episode descriptions.
                description = if (e.has("description")) e.getString("description") else null,
            )
        }
        return PodcastLibrary(podcasts = podcasts, episodes = episodes)
    }

    private fun decodeState(library: PodcastLibrary, json: JSONObject): PodcastLibrary {
        val positions = json.optJSONObject("positions")
        // Absent in files written before the history.
        val heard = json.optJSONObject("heard")
        return library.copy(
            played = json.optJSONArray("played")?.strings()?.toSet().orEmpty(),
            positions = positions?.keys()?.asSequence()?.associateWith { positions.getLong(it) }.orEmpty(),
            heard = heard?.keys()?.asSequence()?.associateWith { heard.getLong(it) }.orEmpty(),
        )
    }

    private fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }
    private fun JSONArray.strings(): List<String> = List(length()) { getString(it) }

    private companion object {
        const val VERSION = 1
    }
}
