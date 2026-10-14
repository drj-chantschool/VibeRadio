package app.viberadio.feedback

/** The persisted total of the [ListeningClock]. */
interface ListeningClockStore {
    /** Total song listening time so far, in ms; 0 if never saved. */
    fun read(): Long

    fun write(totalMs: Long)
}

/**
 * Song listening time: a clock that only runs while a song is actually playing. The shuffle
 * measures "how long since a song was last heard" on it, so a night away (or a podcast) doesn't
 * age every song by the same amount and wash out which ones were heard recently.
 *
 * Driven from the player: [start] while a song plays, [stop] otherwise, [checkpoint] now and then
 * so a crash loses little. Thread-safe; [now] never touches the disk once [load]ed.
 *
 * [save] receives the total to persist; the caller decides which thread writes it.
 */
class ListeningClock(
    private val store: ListeningClockStore,
    private val save: (totalMs: Long) -> Unit = store::write,
    /** A monotonic ms clock (not wall-clock time), so changing the time can't move it. */
    private val elapsed: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private var baseMs = 0L

    /** [elapsed] when the clock last started, or null while stopped. */
    private var runningSince: Long? = null

    private val loaded by lazy { baseMs = runCatching(store::read).getOrDefault(0L).coerceAtLeast(0) }

    /** Reads the stored total. Blocking; call off the main thread, e.g. at startup. */
    @Synchronized
    fun load() = loaded

    /** Total song listening time, in ms. */
    @Synchronized
    fun now(): Long {
        load()
        return baseMs + (runningSince?.let { (elapsed() - it).coerceAtLeast(0) } ?: 0)
    }

    val isRunning: Boolean
        @Synchronized get() = runningSince != null

    @Synchronized
    fun start() {
        load()
        if (runningSince == null) runningSince = elapsed()
    }

    @Synchronized
    fun stop() {
        if (runningSince == null) return
        baseMs = now()
        runningSince = null
        save(baseMs)
    }

    /** Persists the running total without stopping. */
    @Synchronized
    fun checkpoint() {
        if (runningSince != null) save(now())
    }
}
