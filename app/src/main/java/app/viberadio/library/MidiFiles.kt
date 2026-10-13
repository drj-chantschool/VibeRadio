package app.viberadio.library

/**
 * Recognises MIDI files so the library scan can skip them.
 *
 * MIDI holds note events, not recorded audio, so none of the song-side stages (feature
 * extraction, loudness balancing, ducking) can work on it without a synthesiser. MediaStore
 * still lists these files as audio, often with IS_MUSIC set, so they have to be filtered out
 * explicitly. A file counts as MIDI if either its MIME type or its file extension says so,
 * because media scanners don't always agree on the MIME type.
 */
object MidiFiles {

    val MIME_TYPES = setOf("audio/midi", "audio/x-midi", "audio/sp-midi")

    /** .kar is karaoke MIDI, .rmi is RIFF-wrapped MIDI. */
    val EXTENSIONS = setOf("mid", "midi", "kar", "rmi")

    fun isMidi(mimeType: String?, displayName: String?): Boolean {
        val mime = mimeType?.substringBefore(';')?.trim()?.lowercase()
        if (mime != null && mime in MIME_TYPES) return true
        val ext = displayName?.substringAfterLast('.', missingDelimiterValue = "")?.lowercase()
        return !ext.isNullOrEmpty() && ext in EXTENSIONS
    }
}
