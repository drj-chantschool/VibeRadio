package app.viberadio.diagnostics

/**
 * The Android way into developer mode: tap the version line [taps] times in a row. A pause longer
 * than [resetAfterMs] (or the clock going backward) starts the count over. Pure; the settings page
 * turns each [Result] into a toast.
 */
class DevModeUnlock(
    private val taps: Int = TAPS,
    private val resetAfterMs: Long = RESET_AFTER_MS,
    /** Show the countdown once this many taps or fewer are left. */
    private val countdownFrom: Int = COUNTDOWN_FROM,
) {
    sealed interface Result {
        /** Keep tapping; nothing to show yet. */
        data object Quiet : Result

        /** [remaining] more taps to go. */
        data class Countdown(val remaining: Int) : Result

        /** This tap turned developer mode on. */
        data object Unlocked : Result

        /** Developer mode was already on. */
        data object AlreadyOn : Result
    }

    private var count = 0
    private var lastTapMs: Long? = null

    fun tap(nowMs: Long, alreadyOn: Boolean): Result {
        if (alreadyOn) {
            reset()
            return Result.AlreadyOn
        }
        val last = lastTapMs
        if (last == null || nowMs < last || nowMs - last > resetAfterMs) count = 0
        lastTapMs = nowMs
        count++
        val remaining = taps - count
        return when {
            remaining <= 0 -> {
                reset()
                Result.Unlocked
            }
            remaining <= countdownFrom -> Result.Countdown(remaining)
            else -> Result.Quiet
        }
    }

    private fun reset() {
        count = 0
        lastTapMs = null
    }

    companion object {
        const val TAPS = 7
        const val RESET_AFTER_MS = 3_000L
        const val COUNTDOWN_FROM = 3
    }
}
