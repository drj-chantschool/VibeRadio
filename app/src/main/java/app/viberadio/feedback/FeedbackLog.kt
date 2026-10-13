package app.viberadio.feedback

/** Where feedback events are kept. Append-only: nothing is ever rewritten. Blocking; call off the main thread. */
interface FeedbackLog {
    fun append(event: FeedbackEvent)

    /** Every event in the order it was appended. Unreadable entries are skipped. */
    fun readAll(): List<FeedbackEvent>
}
