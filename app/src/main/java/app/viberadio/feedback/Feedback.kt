package app.viberadio.feedback

/** A thumbs press on the current song (#13). Songs only; podcasts never enter this flow. */
enum class Thumb { UP, DOWN }

/**
 * One line of the feedback log. The log is append-only: every press is its own event, so the
 * learner (#14, #15) can read seasonal patterns instead of a collapsed score.
 */
data class FeedbackEvent(
    /** Content hash of the audio file ([ContentHash]): survives moves, renames and rescans. */
    val trackHash: String,
    val thumb: Thumb,
    /** When the press happened, epoch milliseconds. */
    val atMs: Long,
    /**
     * The device's UTC offset at [atMs], in minutes. Kept so time of day and day of week can be
     * read in the listener's local time even after travel or a DST change.
     */
    val utcOffsetMinutes: Int,
    /** The channel the song was playing on. */
    val channelId: String,
    /**
     * The separation EQ module in effect at the press (#2): its ID while a podcast played over
     * the music, null otherwise or for presses from before it was recorded.
     */
    val separationEq: String? = null,
)
