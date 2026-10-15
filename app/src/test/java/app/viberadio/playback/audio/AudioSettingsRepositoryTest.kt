package app.viberadio.playback.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSettingsRepositoryTest {

    private class FakeStore(var saved: AudioSettings? = null) : AudioSettingsStore {
        override fun read(): AudioSettings? = saved
        override fun write(settings: AudioSettings) {
            saved = settings
        }
    }

    private fun repository(store: AudioSettingsStore) =
        AudioSettingsRepository(store, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)

    @Test
    fun `defaults until changed`() {
        val defaults = repository(FakeStore()).settings.value
        assertEquals(AudioSettings.DEFAULT, defaults)
        assertEquals(1_500L, defaults.rampMs)
        assertEquals(-18f, defaults.targetLufs)
        assertEquals(-10f, defaults.mixingFactorDb)
        assertEquals(ToneCurve.FLAT, defaults.musicTone)
        assertEquals(ToneCurve.FLAT, defaults.podcastTone)
    }

    @Test
    fun `target loudness and mixing factor are range-checked`() {
        val repo = repository(FakeStore())
        assertTrue(repo.update { it.copy(targetLufs = -23f, mixingFactorDb = -6f) })
        assertFalse(repo.update { it.copy(targetLufs = -5f) })
        assertFalse(repo.update { it.copy(mixingFactorDb = 3f) })
        assertEquals(-23f, repo.settings.value.targetLufs)
        assertEquals(-6f, repo.settings.value.mixingFactorDb)
    }

    @Test
    fun `a valid change is applied and saved`() {
        val store = FakeStore()
        val repo = repository(store)
        val bright = ToneCurve.FLAT.with(9, 6f)
        assertTrue(repo.update { it.withTone(AudioStream.MUSIC, bright) })
        assertEquals(bright, repo.settings.value.musicTone)
        assertEquals(bright, store.saved!!.musicTone)
        assertEquals(bright, repository(store).settings.value.tone(AudioStream.MUSIC))
        assertEquals(ToneCurve.FLAT, repo.settings.value.podcastTone)
    }

    @Test
    fun `tone bands are limited to 12 dB either way`() {
        val repo = repository(FakeStore())
        assertTrue(repo.update { it.withTone(AudioStream.PODCAST, ToneCurve.FLAT.with(0, -12f)) })
        assertFalse(repo.update { it.withTone(AudioStream.PODCAST, ToneCurve.FLAT.with(0, 12.5f)) })
        assertFalse(repo.update { it.withTone(AudioStream.PODCAST, ToneCurve(listOf(0f, 0f))) })
        assertEquals(-12f, repo.settings.value.podcastTone.bandsDb[0])
    }

    @Test
    fun `an invalid change is refused`() {
        val repo = repository(FakeStore())
        assertFalse(repo.update { it.copy(rampMs = -1) })
        assertEquals(AudioSettings.DEFAULT, repo.settings.value)
    }

    @Test
    fun `restore defaults keeps the tone curves, restore tone keeps the figures`() {
        val tone = ToneCurve.FLAT.with(3, -4f)
        val store = FakeStore(AudioSettings.DEFAULT.copy(rampMs = 3_000, musicTone = tone))
        val repo = repository(store)
        assertEquals(3_000L, repo.settings.value.rampMs)
        repo.restoreDefaults()
        assertEquals(AudioSettings.DEFAULT.copy(musicTone = tone), repo.settings.value)
        assertEquals(AudioSettings.DEFAULT.copy(musicTone = tone), store.saved)
        repo.update { it.copy(rampMs = 500) }
        repo.restoreTone()
        assertEquals(AudioSettings.DEFAULT.copy(rampMs = 500), repo.settings.value)
    }

    @Test
    fun `invalid saved settings fall back to the defaults`() {
        val repo = repository(FakeStore(AudioSettings.DEFAULT.copy(targetLufs = 0f)))
        assertEquals(AudioSettings.DEFAULT, repo.settings.value)
    }

    @Test
    fun `largest boost is what the preamp takes off`() {
        assertEquals(0f, ToneCurve.FLAT.maxBoostDb)
        assertEquals(0f, ToneCurve.FLAT.with(2, -6f).maxBoostDb)
        assertEquals(5f, ToneCurve.FLAT.with(2, 5f).with(7, 3f).maxBoostDb)
    }
}
