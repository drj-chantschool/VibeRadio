package app.viberadio.diagnostics

import app.viberadio.channels.Channel
import app.viberadio.channels.ChannelType
import app.viberadio.feedback.FeedbackEvent
import app.viberadio.feedback.PlayRecord
import app.viberadio.feedback.Thumb
import app.viberadio.learning.FeatureLayout
import app.viberadio.playback.ChannelAiring
import app.viberadio.playback.PodcastEvent
import app.viberadio.playback.audio.AudioSettings
import app.viberadio.playback.audio.TrackLevel
import app.viberadio.podcasts.Episode
import app.viberadio.podcasts.Podcast
import app.viberadio.podcasts.PodcastLibrary
import app.viberadio.track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsBundleTest {

    private val a = track("Rock").copy(title = "Hello, \"World\"", artist = "Band")
    private val b = track("Jazz")

    private val show = Podcast("https://feed", "Show", setOf("podcasts", "breeze"), lastRefreshedMs = 0, pauseMusic = true)
    private val podcasts = PodcastLibrary(
        podcasts = listOf(show),
        episodes = listOf(
            Episode("e1", show.feedUrl, "First", 1_000, "https://e1.mp3", 60_000),
            Episode("e2", show.feedUrl, "Second", 2_000, "https://e2.mp3", null),
            Episode("e0", show.feedUrl, "Old", 500, "https://e0.mp3", null),
        ),
        played = setOf("e0"),
        positions = mapOf("e2" to 5_000L),
    )

    private fun snapshot(
        feedback: List<FeedbackEvent> = listOf(
            FeedbackEvent("hashA", Thumb.UP, 1_000, -300, "breeze"),
            FeedbackEvent("hashA", Thumb.DOWN, 2_000, -300, "all-songs", separationEq = "graphic"),
        ),
        podcastsJson: String? = """{"version":1}""",
        podcastStateJson: String? = """{"played":[]}""",
    ) = DiagnosticsSnapshot(
        appVersion = "1.2.3",
        // 2026-10-15T13:12:03Z, 08:12:03 at UTC−5
        exportedAtMs = 1_792_069_923_000,
        utcOffsetMinutes = -300,
        audio = AudioSettings.DEFAULT,
        resumeOnBluetooth = true,
        channelNames = listOf(ChannelNameRow("breeze", "The Breeze", "Work")),
        lastChannelId = "breeze",
        libraryScanned = true,
        channels = listOf(
            Channel("breeze", "The Breeze", ChannelType.MUSIC_ONLY, listOf(a, b)),
            Channel("all-songs", "All songs", ChannelType.MUSIC_ONLY, listOf(a, b)),
        ),
        trackHashes = mapOf(a.uri to "hashA"),
        feedback = feedback,
        playHistory = mapOf("hashA" to PlayRecord(3_000, 40)),
        listeningClockMs = 50,
        airings = listOf(ChannelAiring("breeze", "hashA", 180_000, 3_000)),
        levels = mapOf("song:hashA" to TrackLevel(-14f, 0.5f), "song:silent" to TrackLevel(Float.NEGATIVE_INFINITY, 0f)),
        podcastsJson = podcastsJson,
        podcastStateJson = podcastStateJson,
        podcasts = podcasts,
        downloadedEpisodes = setOf("e1"),
        podcastEvents = listOf(
            PodcastEvent(3_000, 40, "breeze", "start", "e2", "mode=overlay trigger=mid_song"),
            PodcastEvent(4_000, 50, "breeze", "wait", null, "cause=tune rule=music_ratio wait=boundary"),
        ),
    )

    private fun files(s: DiagnosticsSnapshot = snapshot()) = DiagnosticsBundle.build(s).associateBy { it.name }

    @Test
    fun `folder is named by local export time`() {
        assertEquals("2026-10-15_081203", DiagnosticsBundle.folderName(1_792_069_923_000, -300))
    }

    @Test
    fun `bundle has every file, podcasts only when there are some`() {
        assertEquals(
            setOf(
                "README.md", "summary.json", "settings.json", "feedback.csv", "play_history.csv", "channel_airings.csv",
                "features.csv", "channels.csv", "library.csv", "levels.csv", "podcasts.json", "podcast_state.json",
                "podcast_shows.csv", "podcast_queues.csv", "podcast_events.csv",
            ),
            files().keys,
        )
        assertFalse("podcasts.json" in files(snapshot(podcastsJson = null)))
        assertFalse("podcast_state.json" in files(snapshot(podcastStateJson = null)))
        assertEquals("""{"played":[]}""", files().getValue("podcast_state.json").text)
        assertEquals("""{"version":1}""", files().getValue("podcasts.json").text)
    }

    @Test
    fun `feedback rows keep the log order and show local time`() {
        val lines = files().getValue("feedback.csv").text.lines()
        assertEquals("track_hash,thumb,at_ms,at_local,utc_offset_minutes,channel_id,separation_eq", lines[0])
        assertEquals("hashA,UP,1000,1969-12-31T19:00:01-05:00,-300,breeze,", lines[1])
        assertEquals("hashA,DOWN,2000,1969-12-31T19:00:02-05:00,-300,all-songs,graphic", lines[2])
    }

    @Test
    fun `features are rebuilt from the log with named columns`() {
        val lines = files().getValue("features.csv").text.trim().lines()
        val header = lines[0].split(",")
        assertEquals(1 + FeatureLayout.SIZE, header.size)
        assertEquals(listOf("track_hash", "breeze_up", "breeze_down"), header.take(3))
        val row = lines[1].split(",")
        assertEquals("hashA", row[0])
        assertEquals("1.0", row[header.indexOf("breeze_up")])
        assertEquals("1.0", row[header.indexOf("all-songs_down")])
        assertEquals("0.0", row[header.indexOf("buzz_up")])
    }

    @Test
    fun `library quotes awkward text and leaves unknown hashes empty`() {
        val lines = files().getValue("library.csv").text.trim().lines()
        assertEquals(3, lines.size)
        assertTrue(lines[1].startsWith("${a.id},hashA,\"Hello, \"\"World\"\"\",Band,,Rock,"))
        assertTrue(lines[2].startsWith("${b.id},,"))
    }

    @Test
    fun `channels list one row per channel and song`() {
        val lines = files().getValue("channels.csv").text.trim().lines()
        assertEquals(listOf("channel_id,track_hash,track_id", "breeze,hashA,${a.id}", "breeze,,${b.id}", "all-songs,hashA,${a.id}", "all-songs,,${b.id}"), lines)
    }

    @Test
    fun `levels show the gain at the current target, empty where unmeasurable`() {
        val lines = files().getValue("levels.csv").text.trim().lines()
        assertEquals("key,loudness_lufs,peak,peak_dbfs,gain_db_at_target", lines[0])
        // Sorted by key: "song:hashA" before "song:silent". −14 LUFS to −18 is a 4 dB cut.
        assertTrue(lines[1].startsWith("song:hashA,-14.0,0.5,"))
        assertTrue(lines[1].endsWith(",-4.0"))
        assertEquals("song:silent,,0.0,,0.0", lines[2])
    }

    @Test
    fun `summary counts what's there`() {
        val summary = files().getValue("summary.json").text
        assertTrue(summary.contains("\"app_version\": \"1.2.3\""))
        assertTrue(summary.contains("\"exported_at\": \"2026-10-15T08:12:03-05:00\""))
        assertTrue(summary.contains("\"feedback_events\": 2"))
        assertTrue(summary.contains("\"library_songs\": 2"))
        assertTrue(summary.contains("\"library_songs_hashed\": 1"))
        assertTrue(summary.contains("\"feature_layout_version\": ${FeatureLayout.VERSION}"))
    }

    @Test
    fun `empty log has no feedback range`() {
        assertTrue(files(snapshot(feedback = emptyList())).getValue("summary.json").text.contains("\"feedback_range\": null"))
    }

    @Test
    fun `settings carry channel IDs with both names`() {
        val settings = files().getValue("settings.json").text
        assertTrue(settings.contains("\"id\": \"breeze\""))
        assertTrue(settings.contains("\"default_name\": \"The Breeze\""))
        assertTrue(settings.contains("\"name\": \"Work\""))
        assertTrue(settings.contains("\"is_default\": true"))
    }

    @Test
    fun `settings carry both tone curves and every separation module's figures`() {
        val settings = files().getValue("settings.json").text
        assertTrue(settings.contains("\"music_tone_db\": ["))
        assertTrue(settings.contains("\"podcast_tone_db\": ["))
        assertTrue(settings.contains("\"module\": \"band_pass_notch\""))
        assertTrue(settings.contains("\"music_depth_db\": -9.0"))
        assertTrue(settings.contains("\"podcast_16k\": -12.0"))
    }

    @Test
    fun `settings carry the podcast gap rule and every rule's figures`() {
        val settings = files().getValue("settings.json").text
        assertTrue(settings.contains("\"rule\": \"tracks_by_length\""))
        assertTrue(settings.contains("\"long_minutes\": 15.0"))
        assertTrue(settings.contains("\"ratio\": 0.5"))
    }

    @Test
    fun `podcast shows list channels, settings and episode counts`() {
        val lines = files().getValue("podcast_shows.csv").text.trim().lines()
        assertEquals("feed_url,title,channels,pause_music,speed,priority,skip_intro_s,episodes,unplayed,downloaded,last_refreshed_utc", lines[0])
        assertEquals("https://feed,Show,breeze podcasts,true,1.0,0,0,3,2,1,", lines[1])
    }

    @Test
    fun `podcast queues list each channel's next episodes in queue order`() {
        val lines = files().getValue("podcast_queues.csv").text.trim().lines()
        assertEquals("channel_id,position,episode_id,show,title,published_utc,duration_ms,resume_ms,downloaded,pause_music", lines[0])
        // The started episode first, then the oldest; the played one not at all.
        assertEquals("breeze,0,e2,Show,Second,1970-01-01T00:00:02Z,,5000,false,true", lines[1])
        assertEquals("breeze,1,e1,Show,First,1970-01-01T00:00:01Z,60000,,true,true", lines[2])
        assertEquals("podcasts,0,e2,Show,Second,1970-01-01T00:00:02Z,,5000,false,true", lines[3])
        assertEquals(5, lines.size)
    }

    @Test
    fun `podcast events name the episode and show local time`() {
        val lines = files().getValue("podcast_events.csv").text.trim().lines()
        assertEquals("at_ms,at_local,on_air_ms,channel_id,event,episode_id,show,episode_title,detail", lines[0])
        assertEquals("3000,1969-12-31T19:00:03-05:00,40,breeze,start,e2,Show,Second,mode=overlay trigger=mid_song", lines[1])
        assertEquals("4000,1969-12-31T19:00:04-05:00,50,breeze,wait,,,,cause=tune rule=music_ratio wait=boundary", lines[2])
        assertTrue(files().getValue("summary.json").text.contains("\"podcast_events\": 2"))
    }

    @Test
    fun `json escapes text and writes non-finite numbers as null`() {
        assertEquals(
            "{\n  \"a\": \"q\\\"\\n\\u0001\",\n  \"b\": null,\n  \"c\": [],\n  \"d\": [\n    1,\n    true\n  ]\n}\n",
            Json.encode(linkedMapOf("a" to "q\"\n\u0001", "b" to Double.NaN, "c" to emptyList<Int>(), "d" to listOf(1, true))),
        )
    }

    @Test
    fun `csv quotes only when needed`() {
        assertEquals("plain", Csv.field("plain"))
        assertEquals("\"a,b\"", Csv.field("a,b"))
        assertEquals("\"line\nbreak\"", Csv.field("line\nbreak"))
        assertEquals("", Csv.field(null))
        assertEquals("", Csv.field(Float.NaN))
    }
}
