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
        assertEquals(1_000f, defaults.podcastLowHz)
        assertEquals(4_000f, defaults.podcastHighHz)
        assertEquals(700f, defaults.musicLowHz)
        assertEquals(5_700f, defaults.musicHighHz)
        assertEquals(-9f, defaults.musicDepthDb)
        assertEquals(1_500L, defaults.rampMs)
    }

    @Test
    fun `a valid change is applied and saved`() {
        val store = FakeStore()
        val repo = repository(store)
        assertTrue(repo.update { it.copy(musicDepthDb = -12f) })
        assertEquals(-12f, repo.settings.value.musicDepthDb)
        assertEquals(-12f, store.saved!!.musicDepthDb)
        assertEquals(-12f, repository(store).settings.value.musicDepthDb)
    }

    @Test
    fun `an invalid change is refused`() {
        val repo = repository(FakeStore())
        assertFalse(repo.update { it.copy(podcastLowHz = 5_000f) })
        assertEquals(AudioSettings.DEFAULT, repo.settings.value)
    }

    @Test
    fun `restore defaults`() {
        val store = FakeStore(AudioSettings.DEFAULT.copy(rampMs = 3_000))
        val repo = repository(store)
        assertEquals(3_000L, repo.settings.value.rampMs)
        repo.restoreDefaults()
        assertEquals(AudioSettings.DEFAULT, repo.settings.value)
        assertEquals(AudioSettings.DEFAULT, store.saved)
    }

    @Test
    fun `invalid saved settings fall back to the defaults`() {
        val repo = repository(FakeStore(AudioSettings.DEFAULT.copy(musicLowHz = 9_000f)))
        assertEquals(AudioSettings.DEFAULT, repo.settings.value)
    }
}
