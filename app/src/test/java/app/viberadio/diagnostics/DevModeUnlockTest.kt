package app.viberadio.diagnostics

import app.viberadio.diagnostics.DevModeUnlock.Result
import org.junit.Assert.assertEquals
import org.junit.Test

class DevModeUnlockTest {

    private fun tapRun(unlock: DevModeUnlock, times: Int, startMs: Long = 0, gapMs: Long = 200): List<Result> =
        (0 until times).map { unlock.tap(startMs + it * gapMs, alreadyOn = false) }

    @Test
    fun `seven taps unlock, counting down for the last three`() {
        assertEquals(
            listOf(Result.Quiet, Result.Quiet, Result.Quiet, Result.Countdown(3), Result.Countdown(2), Result.Countdown(1), Result.Unlocked),
            tapRun(DevModeUnlock(), 7),
        )
    }

    @Test
    fun `a long pause starts the count over`() {
        val unlock = DevModeUnlock()
        tapRun(unlock, 5)
        assertEquals(Result.Quiet, unlock.tap(800 + DevModeUnlock.RESET_AFTER_MS + 1, alreadyOn = false))
    }

    @Test
    fun `the clock going backward starts the count over`() {
        val unlock = DevModeUnlock()
        tapRun(unlock, 6, startMs = 10_000)
        assertEquals(Result.Quiet, unlock.tap(0, alreadyOn = false))
    }

    @Test
    fun `already on says so and doesn't count`() {
        val unlock = DevModeUnlock()
        tapRun(unlock, 6)
        assertEquals(Result.AlreadyOn, unlock.tap(1_300, alreadyOn = true))
        assertEquals(Result.Quiet, unlock.tap(1_400, alreadyOn = false))
    }

    @Test
    fun `after unlocking, the count starts fresh`() {
        val unlock = DevModeUnlock()
        tapRun(unlock, 7)
        assertEquals(Result.Unlocked, tapRun(unlock, 7, startMs = 2_000).last())
    }
}
