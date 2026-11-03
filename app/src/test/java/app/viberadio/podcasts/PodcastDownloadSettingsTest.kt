package app.viberadio.podcasts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PodcastDownloadSettingsTest {

    private class FakeStore(
        var count: Int? = null,
        var limit: Long? = null,
        var pinned: Set<String> = emptySet(),
    ) : PodcastDownloadStore {
        override fun readCount() = count
        override fun writeCount(count: Int) {
            this.count = count
        }
        override fun readLimitBytes() = limit
        override fun writeLimitBytes(bytes: Long?) {
            limit = bytes
        }
        override fun readPinned() = pinned
        override fun writePinned(ids: Set<String>) {
            pinned = ids
        }
    }

    private fun settings(store: PodcastDownloadStore) =
        PodcastDownloadSettings(store, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)

    @Test
    fun `starts on 3 episodes and no limit`() = runBlocking {
        val s = settings(FakeStore())
        s.awaitLoaded()
        assertEquals(3, s.count.value)
        assertNull(s.limitBytes.value)
        assertTrue(s.pinned.isEmpty())
    }

    @Test
    fun `more than the warning level is allowed, only an absurd number isn't`() {
        val s = settings(FakeStore())
        assertTrue(s.set(PodcastDownloadSettings.WARN_ABOVE + 1))
        assertTrue(s.set(0))
        assertFalse(s.set(-1))
        assertFalse(s.set(PodcastDownloadSettings.RANGE.last + 1))
        assertEquals(0, s.count.value)
    }

    @Test
    fun `count, limit and pins are saved and survive a restart`() = runBlocking {
        val store = FakeStore()
        val s = settings(store)
        s.set(25)
        s.setLimitBytes(5_000_000_000)
        s.setPinned(setOf("a", "b"))
        val again = settings(store)
        again.awaitLoaded()
        assertEquals(25, again.count.value)
        assertEquals(5_000_000_000, again.limitBytes.value)
        assertEquals(setOf("a", "b"), again.pinned)
    }

    @Test
    fun `a limit of zero means none`() = runBlocking {
        val store = FakeStore(limit = 1_000_000_000)
        val s = settings(store)
        s.awaitLoaded()
        assertTrue(s.setLimitBytes(0))
        assertNull(s.limitBytes.value)
        assertNull(store.limit)
        assertFalse(s.setLimitBytes(-5))
    }
}
