package app.viberadio.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListeningClockTest {

    private class MemoryStore(var total: Long = 0) : ListeningClockStore {
        val writes = mutableListOf<Long>()
        override fun read() = total
        override fun write(totalMs: Long) {
            total = totalMs
            writes += totalMs
        }
    }

    private var elapsed = 1_000_000L
    private val store = MemoryStore()

    private fun clock() = ListeningClock(store, elapsed = { elapsed })

    @Test
    fun `runs only while started`() {
        val c = clock()
        elapsed += 5_000
        assertEquals(0L, c.now())
        c.start()
        elapsed += 7_000
        assertEquals(7_000L, c.now())
        c.stop()
        elapsed += 60_000
        assertEquals(7_000L, c.now())
        c.start()
        elapsed += 1_000
        assertEquals(8_000L, c.now())
    }

    @Test
    fun `stop saves the total and a restart carries on from it`() {
        val c = clock()
        c.start()
        elapsed += 3_000
        c.stop()
        assertEquals(listOf(3_000L), store.writes)
        elapsed += 999_999
        assertEquals(3_000L, clock().now())
    }

    @Test
    fun `starting twice doesn't reset, stopping while stopped doesn't save`() {
        val c = clock()
        c.stop()
        assertTrue(store.writes.isEmpty())
        c.start()
        elapsed += 2_000
        c.start()
        elapsed += 2_000
        assertEquals(4_000L, c.now())
        assertTrue(c.isRunning)
    }

    @Test
    fun `checkpoint saves without stopping`() {
        val c = clock()
        c.checkpoint()
        assertTrue("nothing to save while stopped", store.writes.isEmpty())
        c.start()
        elapsed += 15_000
        c.checkpoint()
        assertEquals(listOf(15_000L), store.writes)
        assertTrue(c.isRunning)
        elapsed += 1_000
        c.stop()
        assertFalse(c.isRunning)
        assertEquals(16_000L, store.total)
    }

    @Test
    fun `a failing store reads as zero`() {
        val broken = object : ListeningClockStore {
            override fun read(): Long = error("disk")
            override fun write(totalMs: Long) = Unit
        }
        assertEquals(0L, ListeningClock(broken, elapsed = { elapsed }).now())
    }
}
