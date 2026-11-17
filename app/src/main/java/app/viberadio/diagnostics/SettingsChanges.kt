package app.viberadio.diagnostics

import app.viberadio.dial.ChannelNames
import app.viberadio.library.FolderExclusions
import app.viberadio.library.PodcastFolder
import app.viberadio.playback.PodcastGapSettings
import app.viberadio.playback.audio.AudioSettings
import app.viberadio.playback.audio.SeparationSettings
import app.viberadio.playback.audio.ToneBands
import java.io.File

/**
 * What the player was doing, and what the loudness compensator (#1, #2) was doing to it, at the
 * moment a setting changed. Lets a change be lined up with the song or episode on air.
 */
data class NowPlaying(
    /** The session player was playing (not paused, stopped or not running). */
    val playing: Boolean = false,
    val channelId: String? = null,
    /** Media ID of the item in the session player (`track:<id>` or `episode:<id>`). */
    val mediaId: String? = null,
    val title: String? = null,
    /** Content hash of the song, when it's a song with a known hash. */
    val songHash: String? = null,
    /** Measured integrated loudness of the session player's item, if measured. */
    val levelLufs: Float? = null,
    /** Its sample peak, 1.0 = full scale. */
    val peak: Float? = null,
    /** The fixed gain leveling applies to it at the current target; null when not measured (0 dB is applied). */
    val gainDb: Float? = null,
    /** The episode on the overlay player, if any. */
    val overlayEpisodeId: String? = null,
    val overlayLufs: Float? = null,
    val overlayGainDb: Float? = null,
    /** The mixing factor being applied to the music (0 unless a podcast plays over it). */
    val mixDb: Float = 0f,
    /** The separation module in effect (only with a podcast over the music). */
    val separation: String? = null,
    /** The leveling target in effect, LUFS. */
    val targetLufs: Float? = null,
)

/** What a [app.viberadio.playback.PlaybackService] player holds at an instant, before [NowPlaying] adds the leveling figures. */
data class PlayerSnapshot(
    val playing: Boolean,
    val mediaId: String?,
    val channelId: String?,
    val title: String?,
)

/** One setting that changed, with what was on air then. */
data class SettingsChange(
    /** Wall clock, when the new value was in place. */
    val atMs: Long,
    /** Dotted key of the setting, e.g. `audio.mixing_factor_db`. */
    val key: String,
    val from: String,
    val to: String,
    val now: NowPlaying = NowPlaying(),
) {
    companion object {
        private const val COLUMNS = 18

        /** One tab-separated line, without the newline; tabs and newlines in text become spaces. */
        fun encode(c: SettingsChange): String {
            val n = c.now
            return listOf(
                c.atMs.toString(), c.key, c.from, c.to,
                n.playing.toString(), n.channelId.orEmpty(), n.mediaId.orEmpty(), n.title.orEmpty(), n.songHash.orEmpty(),
                n.levelLufs.text(), n.peak.text(), n.gainDb.text(),
                n.overlayEpisodeId.orEmpty(), n.overlayLufs.text(), n.overlayGainDb.text(),
                n.mixDb.toString(), n.separation.orEmpty(), n.targetLufs.text(),
            ).joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ') }
        }

        /** The change on [line], or null if it isn't one. */
        fun decode(line: String): SettingsChange? {
            val p = line.split('\t')
            if (p.size != COLUMNS) return null
            return SettingsChange(
                atMs = p[0].toLongOrNull() ?: return null,
                key = p[1].ifEmpty { return null },
                from = p[2],
                to = p[3],
                now = NowPlaying(
                    playing = p[4] == "true",
                    channelId = p[5].ifEmpty { null },
                    mediaId = p[6].ifEmpty { null },
                    title = p[7].ifEmpty { null },
                    songHash = p[8].ifEmpty { null },
                    levelLufs = p[9].toFloatOrNull(),
                    peak = p[10].toFloatOrNull(),
                    gainDb = p[11].toFloatOrNull(),
                    overlayEpisodeId = p[12].ifEmpty { null },
                    overlayLufs = p[13].toFloatOrNull(),
                    overlayGainDb = p[14].toFloatOrNull(),
                    mixDb = p[15].toFloatOrNull() ?: 0f,
                    separation = p[16].ifEmpty { null },
                    targetLufs = p[17].toFloatOrNull(),
                ),
            )
        }

        private fun Float?.text(): String = if (this != null && isFinite()) toString() else ""
    }
}

/**
 * Turns each settings holder into flat `key → text` maps, and compares two of them. Pure. Keys
 * are stable names (also used in the export), values are what a person would read.
 */
object SettingsFlattener {

    fun audio(s: AudioSettings): Map<String, String> = linkedMapOf<String, String>().apply {
        put("audio.target_lufs", s.targetLufs.toString())
        put("audio.mixing_factor_db", s.mixingFactorDb.toString())
        put("audio.ramp_ms", s.rampMs.toString())
        s.musicTone.bandsDb.forEachIndexed { i, db -> put("audio.music_tone_db.${ToneBands.LABELS.getOrElse(i) { i.toString() }}", db.toString()) }
        s.podcastTone.bandsDb.forEachIndexed { i, db -> put("audio.podcast_tone_db.${ToneBands.LABELS.getOrElse(i) { i.toString() }}", db.toString()) }
    }

    fun separation(s: SeparationSettings): Map<String, String> = linkedMapOf<String, String>().apply {
        put("separation_eq.module", s.selected)
        s.modules.forEach { module -> module.params.forEach { put("separation_eq.${module.id}.${it.key}", module[it.key].toString()) } }
    }

    fun podcastGap(s: PodcastGapSettings): Map<String, String> = linkedMapOf<String, String>().apply {
        put("podcast_gap.rule", s.selected)
        s.rules.forEach { rule -> rule.params.forEach { put("podcast_gap.${rule.id}.${it.key}", rule[it.key].toString()) } }
    }

    fun bluetooth(resumeOnConnect: Boolean): Map<String, String> = mapOf("bluetooth.resume_on_connect" to resumeOnConnect.toString())

    fun folders(exclusions: FolderExclusions, podcastFolder: PodcastFolder): Map<String, String> = mapOf(
        "library.excluded_folders" to exclusions.subtrees.sorted().joinToString(" | "),
        "library.excluded_files_only" to exclusions.filesOnly.sorted().joinToString(" | "),
        "podcasts.folder" to podcastFolder.path.orEmpty(),
    )

    fun channelNames(names: ChannelNames): Map<String, String> =
        names.defaults.keys.sorted().associate { id -> "channel_name.$id" to names.of(id).orEmpty() }

    fun downloads(count: Int, limitBytes: Long?): Map<String, String> = mapOf(
        "podcasts.keep_downloaded" to count.toString(),
        "podcasts.storage_limit_bytes" to (limitBytes?.toString() ?: ""),
    )

    fun diagnostics(developerMode: Boolean, nightly: Boolean): Map<String, String> = mapOf(
        "diagnostics.developer_mode" to developerMode.toString(),
        "diagnostics.nightly_export" to nightly.toString(),
    )

    /** The settings that differ between [before] and [after], in key order: (key, from, to). A key on one side only counts as empty on the other. */
    fun diff(before: Map<String, String>, after: Map<String, String>): List<Triple<String, String, String>> =
        (before.keys + after.keys).sorted().mapNotNull { key ->
            val from = before[key].orEmpty()
            val to = after[key].orEmpty()
            if (from == to) null else Triple(key, from, to)
        }
}

/**
 * The settings-change log on disk: one [SettingsChange.encode] line per change. Keeps the newest
 * [keep]; the file is cut back to them once it holds twice as many.
 */
class FileSettingsChangeStore(private val file: File, private val keep: Int = 5_000) {
    private var lines = -1

    @Synchronized
    fun readAll(): List<SettingsChange> {
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull(SettingsChange::decode)
    }

    @Synchronized
    fun append(changes: List<SettingsChange>) {
        if (changes.isEmpty()) return
        file.parentFile?.mkdirs()
        if (lines < 0) lines = if (file.exists()) file.readLines().size else 0
        file.appendText(changes.joinToString("") { SettingsChange.encode(it) + "\n" })
        lines += changes.size
        if (lines >= 2 * keep) {
            val kept = file.readLines().takeLast(keep)
            file.writeText(kept.joinToString("") { "$it\n" })
            lines = kept.size
        }
    }
}
