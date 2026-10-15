package app.viberadio.library

/**
 * One playable audio item from the device library.
 *
 * Kept free of Android and Media3 types so channel logic runs in plain JVM unit tests.
 */
data class Track(
    val id: Long,
    /** content:// URI of the audio, as a string. */
    val uri: String,
    val title: String,
    val artist: String?,
    val album: String?,
    /** Raw genre tag as stored by MediaStore, or null when the file has none. */
    val genre: String?,
    val durationMs: Long,
    val kind: ContentKind,
    /** File size and modification time (epoch seconds) from MediaStore; a change means the bytes may have changed. */
    val sizeBytes: Long = 0,
    val modifiedSec: Long = 0,
    /**
     * The folder the file is in, `/`-separated, relative to its storage volume ("Music/Rock"; ""
     * at the volume root). Files on a volume other than the main one start with the volume name.
     */
    val folder: String = "",
)

/**
 * What a track is, as far as channel routing cares.
 *
 * Today this comes straight from MediaStore's IS_PODCAST / IS_AUDIOBOOK flags.
 * Issue #6 (podcast music detection) refines PODCAST into finer classes by analysing
 * the audio; issue #3 (channel types) routes on it.
 */
enum class ContentKind {
    MUSIC,
    PODCAST,
    AUDIOBOOK,
}
