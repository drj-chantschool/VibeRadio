package app.viberadio.library

/** Source of the device's audio library. Swappable for tests and for future sources (e.g. podcast feeds). */
fun interface LibraryScanner {
    /** Blocking; call off the main thread. */
    fun scan(): List<Track>
}
