package app.viberadio.feedback

import java.io.InputStream
import java.security.MessageDigest

/**
 * Stable identity for a song: a SHA-256 of the file's bytes. Unlike the MediaStore ID or URI it
 * doesn't change when the file is moved, renamed or the library is rescanned, so the feedback log
 * keeps following the song.
 *
 * It does change if the file's bytes change, e.g. after editing its tags. The value carries an
 * algorithm prefix so a later scheme (say, hashing audio frames only) can live alongside it.
 */
object ContentHash {
    const val PREFIX = "sha256:"

    fun of(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return PREFIX + digest.digest().joinToString("") { "%02x".format(it) }
    }
}
