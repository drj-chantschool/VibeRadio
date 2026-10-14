package app.viberadio.learning

import app.viberadio.channels.Channel
import app.viberadio.channels.ChannelType
import app.viberadio.dial.ChannelLineup
import app.viberadio.dial.ChannelLineup.BREATH
import app.viberadio.dial.ChannelLineup.BREEZE
import app.viberadio.dial.ChannelLineup.BUZZ
import app.viberadio.dial.ChannelLineup.HALL
import app.viberadio.dial.ChannelLineup.SHUFFLE
import app.viberadio.dial.ChannelLineup.VOICE
import app.viberadio.dial.ChannelLineup.ZONE
import app.viberadio.feedback.FeedbackEvent
import app.viberadio.feedback.Thumb
import app.viberadio.feedback.Thumb.DOWN
import app.viberadio.feedback.Thumb.UP
import app.viberadio.library.Track
import app.viberadio.tracks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LearningTest {

    private var t = 0L
    private fun event(hash: String, channel: String, thumb: Thumb) = FeedbackEvent(hash, thumb, t++, 0, channel)

    private fun vector(vararg events: Pair<String, Thumb>): FeatureVector =
        events.fold(FeatureVector.EMPTY) { v, (channel, thumb) -> v + event("h", channel, thumb) }

    // Layout

    @Test
    fun `vector has twelve features, ten of them for channel fit`() {
        assertEquals(12, FeatureLayout.SIZE)
        assertEquals(10, FeatureVector.EMPTY.channelFit.size)
        assertEquals(12, FeatureVector.EMPTY.preference.size)
    }

    @Test
    fun `thumbs count by channel and direction, shuffle last`() {
        val v = vector(BREEZE to UP, BREEZE to UP, HALL to DOWN, SHUFFLE to UP, SHUFFLE to DOWN)
        assertEquals(
            listOf(2f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 1f, 1f),
            v.toFloatArray().toList(),
        )
        assertEquals(v.toFloatArray().take(10), v.channelFit.toList())
    }

    @Test
    fun `thumbs on a channel without a slot change nothing`() {
        assertEquals(FeatureVector.EMPTY, vector(VOICE to UP, "genre:jazz" to DOWN))
    }

    @Test
    fun `vectors rebuild from the log per song`() {
        val log = listOf(event("a", ZONE, UP), event("b", BUZZ, DOWN), event("a", ZONE, UP))
        val vectors = FeatureVectors.from(log)
        assertEquals(2f, vectors.getValue("a")[FeatureLayout.index(ZONE, UP)!!])
        assertEquals(1f, vectors.getValue("b")[FeatureLayout.index(BUZZ, DOWN)!!])
    }

    // Channel fit

    private fun fit(v: FeatureVector) = ThumbRuleChannelFit.channelsFor(v.channelFit)

    @Test
    fun `no feedback ties everywhere, so all five moods`() {
        assertEquals(ChannelLineup.moods.toSet(), fit(FeatureVector.EMPTY))
    }

    @Test
    fun `fewest thumbs down wins, ties included`() {
        val v = vector(BREEZE to DOWN, BUZZ to DOWN, ZONE to DOWN, ZONE to DOWN)
        assertEquals(setOf(BREATH, HALL), fit(v))
    }

    @Test
    fun `any thumbs up keeps a channel despite thumbs down`() {
        val v = vector(BREEZE to DOWN, BREEZE to DOWN, BREEZE to UP, BUZZ to DOWN, ZONE to DOWN, BREATH to DOWN)
        assertEquals(setOf(BREEZE, HALL), fit(v))
    }

    @Test
    fun `shuffle feedback doesn't move a song between moods`() {
        assertEquals(ChannelLineup.moods.toSet(), fit(vector(SHUFFLE to DOWN, SHUFFLE to DOWN)))
    }

    // Guarantees

    @Test
    fun `a model that returns nothing still covers the song`() {
        val models = LearningModels(channelFit = { emptySet() })
        assertEquals(ChannelLineup.moods.toSet(), models.channelsFor(FeatureVector.EMPTY))
    }

    @Test
    fun `preference is floored`() {
        val models = LearningModels(preference = { 0.0 }, preferenceFloor = 0.1)
        assertEquals(0.1, models.preferenceWeight(FeatureVector.EMPTY), 0.0)
        assertEquals(0.1, LearningModels(preference = { Double.NaN }).preferenceWeight(FeatureVector.EMPTY), 0.0)
        assertEquals(3.0, LearningModels(preference = { 3.0 }).preferenceWeight(FeatureVector.EMPTY), 0.0)
    }

    // Placement

    private val songs = tracks("Jazz", 4)
    private fun hashOf(track: Track) = "h${track.id}"

    private fun learner(vararg events: FeedbackEvent) = Learner(LearningModels(), { events.toList() }, ::hashOf)

    private val generated = listOf(
        Channel(BREEZE, "Jazz", ChannelType.MUSIC_ONLY, songs.take(2)),
        Channel(BUZZ, "Everything else", ChannelType.MUSIC_ONLY, songs.drop(2)),
        Channel(SHUFFLE, "All songs", ChannelType.MUSIC_ONLY, songs),
    )

    @Test
    fun `without feedback every song is on every mood, shuffle untouched`() {
        val placed = learner().place(generated)
        assertEquals(ChannelLineup.moods + SHUFFLE, placed.map { it.id })
        placed.forEach { assertEquals(songs, it.tracks) }
        assertSame(generated.last(), placed.last())
    }

    @Test
    fun `a thumbs down moves a song off that channel`() {
        val down = event(hashOf(songs[0]), BREEZE, DOWN)
        val placed = learner(down).place(generated).associateBy { it.id }
        assertEquals(songs.drop(1), placed.getValue(BREEZE).tracks)
        assertEquals(songs, placed.getValue(BUZZ).tracks)
    }

    @Test
    fun `added feedback counts without reloading`() {
        val learner = learner()
        learner.add(event(hashOf(songs[1]), BUZZ, DOWN))
        assertEquals(songs - songs[1], learner.place(generated).single { it.id == BUZZ }.tracks)
    }

    @Test
    fun `an emptied mood stays off the dial`() {
        // One song, thumbed down on four moods: only The Hall is left.
        val one = listOf(Channel(BREEZE, "Jazz", ChannelType.MUSIC_ONLY, songs.take(1)))
        val events = listOf(BREEZE, BUZZ, ZONE, BREATH).map { event(hashOf(songs[0]), it, DOWN) }
        assertEquals(listOf(HALL), learner(*events.toTypedArray()).place(one).map { it.id })
    }

    @Test
    fun `channels without mood slots pass through`() {
        val genreOnly = listOf(Channel("genre:jazz", "Jazz", ChannelType.MUSIC_ONLY, songs))
        assertSame(genreOnly, learner().place(genreOnly))
    }
}
