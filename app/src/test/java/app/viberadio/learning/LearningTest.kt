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
import app.viberadio.features.FeatureBlock
import app.viberadio.features.SimilaritySpace
import app.viberadio.feedback.FeedbackEvent
import app.viberadio.feedback.Thumb
import app.viberadio.feedback.Thumb.DOWN
import app.viberadio.feedback.Thumb.UP
import app.viberadio.library.Track
import app.viberadio.tracks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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

    // Similarity (Similarity plan, Phase 3)

    private val propagated = PropagatedChannelFit()

    private fun neighbour(distance: Double, vararg events: Pair<String, Thumb>) = NeighbourFit(distance, vector(*events).channelFit)

    private fun downs(channel: String, n: Int) = Array(n) { channel to DOWN }

    @Test
    fun `without neighbours the thumb rule decides`() {
        val v = vector(BREEZE to DOWN)
        assertEquals(fit(v), propagated.channelsFor(v.channelFit, emptyList()))
        assertEquals(fit(v), propagated.channelsFor(v.channelFit))
    }

    @Test
    fun `thumbs down on a close neighbour move an unrated song off that channel`() {
        val near = neighbour(0.0, *downs(BUZZ, 5))
        assertEquals(ChannelLineup.moods.toSet() - BUZZ, propagated.channelsFor(FeatureVector.EMPTY.channelFit, listOf(near)))
    }

    @Test
    fun `too little evidence leaves the song where it was`() {
        val few = neighbour(0.0, *downs(BUZZ, 4))
        val far = neighbour(1.0, *downs(BUZZ, 50))
        val all = ChannelLineup.moods.toSet()
        assertEquals(all, propagated.channelsFor(FeatureVector.EMPTY.channelFit, listOf(few)))
        assertEquals(all, propagated.channelsFor(FeatureVector.EMPTY.channelFit, listOf(far)))
    }

    @Test
    fun `thumbs up on similar songs bring a song back to a channel`() {
        val own = vector(BREEZE to DOWN, BUZZ to DOWN)
        assertTrue(BREEZE !in fit(own))
        val liked = neighbour(0.1, *Array(5) { BREEZE to UP })
        assertTrue(BREEZE in propagated.channelsFor(own.channelFit, listOf(liked)))
    }

    @Test
    fun `a song's own thumbs up beats its neighbours`() {
        val own = vector(BUZZ to UP)
        val near = neighbour(0.0, *downs(BUZZ, 20))
        assertTrue(BUZZ in propagated.channelsFor(own.channelFit, listOf(near)))
    }

    @Test
    fun `neighbour weight is capped, so a duplicate can't decide alone`() {
        assertEquals(0.8, propagated.weight(0.0), 1e-9)
        assertTrue(propagated.weight(0.5) < 0.2)
    }

    @Test
    fun `placement borrows thumbs from acoustically similar songs`() {
        // songs 0 and 1 sound alike, as do 2 and 3; song 1 got thumbs down on The Buzz.
        val block = FeatureBlock("t", 1, listOf("x", "y"))
        val index = SimilaritySpace().build(
            listOf(
                block to mapOf(
                    hashOf(songs[0]) to floatArrayOf(1f, 0f), hashOf(songs[1]) to floatArrayOf(1f, 0f),
                    hashOf(songs[2]) to floatArrayOf(-1f, 0f), hashOf(songs[3]) to floatArrayOf(-1f, 0f),
                ),
            ),
        )
        val events = List(5) { event(hashOf(songs[1]), BUZZ, DOWN) }
        val learner = Learner(LearningModels(), { events }, ::hashOf, similarity = { index })
        val placed = learner.place(generated).associateBy { it.id }
        assertEquals(songs.drop(2), placed.getValue(BUZZ).tracks)
        assertEquals(songs, placed.getValue(BREEZE).tracks)
        // Without the index only song 1 moves.
        assertEquals(songs - songs[1], learner(*events.toTypedArray()).place(generated).single { it.id == BUZZ }.tracks)
    }
}
