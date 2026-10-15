package app.viberadio.library

import android.content.ContentResolver
import android.content.ContentUris
import android.database.Cursor
import android.os.Build
import android.os.Environment
import android.provider.MediaStore

/**
 * Reads audio from [MediaStore].
 *
 * Genre handling differs by API level: `MediaStore.Audio.AudioColumns.GENRE` only exists on
 * API 30+. On API 26–29 genres live in a separate table, so we build an id→genre map from
 * `MediaStore.Audio.Genres` and its members.
 *
 * MIDI files are skipped ([MidiFiles]); MediaStore lists them as audio, often as music.
 */
class MediaStoreLibraryScanner(
    private val resolver: ContentResolver,
    /** Tracks shorter than this are treated as ringtones/notification sounds and skipped. */
    private val minDurationMs: Long = 30_000,
) : LibraryScanner {

    override fun scan(): List<Track> {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val columns = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.IS_PODCAST)
            add(MediaStore.Audio.Media.MIME_TYPE)
            add(MediaStore.Audio.Media.DISPLAY_NAME)
            add(MediaStore.Audio.Media.SIZE)
            add(MediaStore.Audio.Media.DATE_MODIFIED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Audio.Media.IS_AUDIOBOOK)
                add(MediaStore.Audio.Media.RELATIVE_PATH)
                add(MediaStore.Audio.Media.VOLUME_NAME)
            } else {
                @Suppress("DEPRECATION") // The file path is the only folder source below API 29.
                add(MediaStore.Audio.Media.DATA)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) add(MediaStore.Audio.Media.GENRE)
        }.toTypedArray()

        @Suppress("DEPRECATION") // Only used to make pre-29 file paths relative, like RELATIVE_PATH.
        val legacyRoot = Environment.getExternalStorageDirectory().absolutePath
        val legacyGenres = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) legacyGenreMap() else emptyMap()

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 OR ${MediaStore.Audio.Media.IS_PODCAST} != 0"
        val tracks = ArrayList<Track>()
        resolver.query(collection, columns, selection, null, null)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val podcastCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_PODCAST)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val modifiedCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            val audiobookCol = c.getColumnIndex(MediaStore.Audio.Media.IS_AUDIOBOOK)
            val relativePathCol = c.getColumnIndex("relative_path")
            val volumeCol = c.getColumnIndex("volume_name")
            val dataCol = c.getColumnIndex("_data")
            val genreCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                c.getColumnIndex(MediaStore.Audio.Media.GENRE)
            } else {
                -1
            }

            while (c.moveToNext()) {
                val duration = c.getLong(durationCol)
                if (duration < minDurationMs) continue
                // MIDI is note data, not audio: skipped, never a song (see MidiFiles).
                if (MidiFiles.isMidi(c.getString(mimeCol), c.getString(nameCol))) continue
                val id = c.getLong(idCol)
                val kind = when {
                    c.flag(podcastCol) -> ContentKind.PODCAST
                    c.flag(audiobookCol) -> ContentKind.AUDIOBOOK
                    else -> ContentKind.MUSIC
                }
                tracks += Track(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id).toString(),
                    title = c.getString(titleCol).orEmpty(),
                    artist = c.getString(artistCol).takeUnless { it == MediaStore.UNKNOWN_STRING },
                    album = c.getString(albumCol),
                    genre = if (genreCol >= 0) c.getString(genreCol) else legacyGenres[id],
                    durationMs = duration,
                    kind = kind,
                    sizeBytes = c.getLong(sizeCol),
                    modifiedSec = c.getLong(modifiedCol),
                    folder = if (relativePathCol >= 0) {
                        LibraryFolderPaths.fromRelativePath(c.getString(relativePathCol), if (volumeCol >= 0) c.getString(volumeCol) else null)
                    } else {
                        LibraryFolderPaths.fromFilePath(if (dataCol >= 0) c.getString(dataCol) else null, legacyRoot)
                    },
                )
            }
        }
        return tracks
    }

    @Suppress("DEPRECATION") // Genres table is the only genre source below API 30.
    private fun legacyGenreMap(): Map<Long, String> {
        val result = HashMap<Long, String>()
        resolver.query(
            MediaStore.Audio.Genres.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Genres._ID, MediaStore.Audio.Genres.NAME),
            null, null, null,
        )?.use { genres ->
            while (genres.moveToNext()) {
                val genreId = genres.getLong(0)
                val name = genres.getString(1) ?: continue
                resolver.query(
                    MediaStore.Audio.Genres.Members.getContentUri("external", genreId),
                    arrayOf(MediaStore.Audio.Genres.Members.AUDIO_ID),
                    null, null, null,
                )?.use { members ->
                    while (members.moveToNext()) {
                        // First genre wins if a track belongs to several.
                        result.putIfAbsent(members.getLong(0), name)
                    }
                }
            }
        }
        return result
    }

    private fun Cursor.flag(column: Int): Boolean =
        column >= 0 && !isNull(column) && getInt(column) != 0
}
