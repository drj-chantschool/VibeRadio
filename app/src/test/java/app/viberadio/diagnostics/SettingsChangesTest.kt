package app.viberadio.diagnostics

import app.viberadio.dial.ChannelNames
import app.viberadio.library.FolderExclusions
import app.viberadio.library.PodcastFolder
import app.viberadio.playback.PodcastGapSettings
import app.viberadio.playback.audio.AudioSettings
import app.viberadio.playback.audio.SeparationSettings
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsChangesTest {

    @Test
    fun `identical settings have no diff`() {
        val a = SettingsFlattener.audio(AudioSettings.DEFAULT)
        assertTrue(SettingsFlattener.diff(a, SettingsFlattener.audio(AudioSettings.DEFAULT)).isEmpty())
    }

    @Test
    fun `a figure and a tone band change as their own keys`() {
        val before = SettingsFlattener.audio(AudioSettings.DEFAULT)
        val changed = AudioSettings.DEFAULT.copy(mixingFactorDb = -12f).let { it.withTone(app.viberadio.playback.audio.AudioStream.MUSIC, it.musicTone.with(5, 3f)) }
        val diff = SettingsFlattener.diff(before, SettingsFlattener.audio(changed))
        assertEquals(
            listOf(
                Triple("audio.mixing_factor_db", "-10.0", "-12.0"),
                Triple("audio.music_tone_db.1k", "0.0", "3.0"),
            ),
            diff,
        )
    }

    @Test
    fun `separation and gap choices are keyed by module and rule`() {
        val sep = SettingsFlattener.diff(
            SettingsFlattener.separation(SeparationSettings.DEFAULT),
            SettingsFlattener.separation(SeparationSettings.DEFAULT.select("graphic")),
        )
        assertEquals(listOf(Triple("separation_eq.module", SeparationSettings.DEFAULT.selected, "graphic")), sep)
        val gap = PodcastGapSettings.DEFAULT
        val other = PodcastGapSettings.DEFAULT.select(gap.rules.first { it.id != gap.selected }.id)
        val diff = SettingsFlattener.diff(SettingsFlattener.podcastGap(gap), SettingsFlattener.podcastGap(other))
        assertEquals(listOf("podcast_gap.rule"), diff.map { it.first })
    }

    @Test
    fun `folders, names and downloads flatten to readable text`() {
        val folders = SettingsFlattener.folders(FolderExclusions(subtrees = setOf("B", "A")), PodcastFolder("Podcasts"))
        assertEquals("A | B", folders["library.excluded_folders"])
        assertEquals("Podcasts", folders["podcasts.folder"])
        val names = ChannelNames(mapOf("breeze" to "The Breeze")).renamed("breeze", "Dawn")
        assertEquals(mapOf("channel_name.breeze" to "Dawn"), SettingsFlattener.channelNames(names))
        assertEquals("", SettingsFlattener.downloads(3, null)["podcasts.storage_limit_bytes"])
    }

    @Test
    fun `a key on one side only counts as empty on the other`() {
        assertEquals(listOf(Triple("a", "", "1"), Triple("b", "2", "")), SettingsFlattener.diff(mapOf("b" to "2"), mapOf("a" to "1")))
    }

    @Test
    fun `changes round-trip, with tabs and newlines flattened`() {
        val change = SettingsChange(
            1_000, "channel_name.breeze", "The Breeze", "Da\twn\nx",
            NowPlaying(true, "breeze", "track:1", "Song", "h", -20f, 0.5f, 2f, "e1", -18f, 0f, -10f, "graphic", -18f),
        )
        assertEquals(change.copy(to = "Da wn x"), SettingsChange.decode(SettingsChange.encode(change)))
        val bare = SettingsChange(1, "audio.ramp_ms", "1500", "2000")
        assertEquals(bare, SettingsChange.decode(SettingsChange.encode(bare)))
        assertNull(SettingsChange.decode(SettingsChange.encode(bare).substringBeforeLast('\t')))
        assertNull(SettingsChange.decode("garbage"))
    }

    @Test
    fun `the store keeps the newest changes once it holds twice as many`() {
        val file = Files.createTempDirectory("settings-changes").toFile().resolve("c.tsv")
        val store = FileSettingsChangeStore(file, keep = 3)
        store.append((0 until 5).map { SettingsChange(it.toLong(), "k", "a", "b") })
        assertEquals(listOf<Long>(0, 1, 2, 3, 4), store.readAll().map { it.atMs })
        store.append(listOf(SettingsChange(5, "k", "a", "b")))
        assertEquals(listOf<Long>(3, 4, 5), store.readAll().map { it.atMs })
    }
}
