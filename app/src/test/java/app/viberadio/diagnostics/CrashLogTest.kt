package app.viberadio.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashLogTest {

    private fun entry(n: Int) = CrashLog.entry(
        // 2026-10-15T13:12:03Z, 08:12:03 at UTC−5
        atMs = 1_792_069_923_000 + n * 1_000L,
        utcOffsetMinutes = -300,
        appVersion = "1.2.3",
        threadName = "main",
        stackTrace = "java.lang.OutOfMemoryError: crash $n\n\tat a.B.c(B.kt:$n)\n",
    )

    @Test
    fun `an entry has a header with local time, version and thread, then the stack trace`() {
        assertEquals(
            "=== crash 2026-10-15T08:12:03-05:00 · app 1.2.3 · thread main ===\n" +
                "java.lang.OutOfMemoryError: crash 0\n\tat a.B.c(B.kt:0)\n",
            entry(0),
        )
    }

    @Test
    fun `appending splits back into the same entries, oldest first`() {
        val log = CrashLog.append(CrashLog.append(null, entry(1)), entry(2))
        assertEquals(listOf(entry(1), entry(2)), CrashLog.entries(log))
        assertTrue(log.startsWith(CrashLog.MARKER))
    }

    @Test
    fun `keeps only the newest entries`() {
        val log = (1..CrashLog.KEEP + 3).fold(null as String?) { acc, n -> CrashLog.append(acc, entry(n)) }
        val entries = CrashLog.entries(log)
        assertEquals(CrashLog.KEEP, entries.size)
        assertEquals(entry(4), entries.first())
        assertEquals(entry(CrashLog.KEEP + 3), entries.last())
    }

    @Test
    fun `an empty or headerless log has no entries`() {
        assertEquals(emptyList<String>(), CrashLog.entries(null))
        assertEquals(emptyList<String>(), CrashLog.entries("  \n"))
        assertEquals(listOf(entry(1)), CrashLog.entries(CrashLog.append("stray text\n", entry(1))))
    }

    @Test
    fun `exit reasons are named by their platform value`() {
        assertEquals("LOW_MEMORY", ExitReasons.name(3))
        assertEquals("CRASH", ExitReasons.name(4))
        assertEquals("ANR", ExitReasons.name(6))
        assertEquals("99", ExitReasons.name(99))
    }

    @Test
    fun `an export is named after its local time`() {
        assertEquals(
            "viberadio-crashes-${DiagnosticsBundle.folderName(1_792_069_923_000, -300)}.txt",
            CrashLog.exportName(1_792_069_923_000, -300),
        )
    }
}
