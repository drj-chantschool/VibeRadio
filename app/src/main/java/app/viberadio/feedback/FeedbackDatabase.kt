package app.viberadio.feedback

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.viberadio.playback.ChannelAiring
import app.viberadio.playback.ChannelAiringStore
import java.io.File

/**
 * `feedback.db`: per-song state keyed on the file's [ContentHash], plus what each song channel
 * last aired. The only Android code in `feedback`; everything else is pure JVM.
 *
 * - `feedback`: the thumbs log, one row per press. Append-only is enforced by the schema:
 *   triggers abort any UPDATE or DELETE, so a later feature can't quietly rewrite history. Rows
 *   come back in insert order (`id`).
 * - `play_history`: one row per song, `last_played` (epoch ms) overwritten on every play.
 * - `track_hashes`: URI → content hash, valid while MediaStore's size and mtime still match, so
 *   play history can be looked up without re-reading every file.
 * - `channel_airings`: per song channel, the song it last put on air (hash, duration, wall-clock
 *   start), for live-radio resume. One row per channel, overwritten.
 *
 * On first creation it imports [legacyFile] (the tab-separated log earlier builds wrote) in the
 * same transaction, then renames that file to `*.imported` so it's never read again.
 */
class FeedbackDatabase(
    context: Context,
    private val legacyFile: File? = null,
) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE feedback (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                track_hash TEXT NOT NULL,
                thumb TEXT NOT NULL CHECK (thumb IN ('UP', 'DOWN')),
                at_ms INTEGER NOT NULL,
                utc_offset_minutes INTEGER NOT NULL,
                channel_id TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX feedback_track_time ON feedback (track_hash, at_ms)")
        for (op in listOf("UPDATE", "DELETE")) {
            db.execSQL(
                """
                CREATE TRIGGER feedback_no_${op.lowercase()} BEFORE $op ON feedback
                BEGIN SELECT RAISE(ABORT, 'feedback is append-only'); END
                """.trimIndent(),
            )
        }
        createPlayHistory(db)
        createChannelAirings(db)
        legacyFile?.let { LegacyTsvLog.read(it) }?.forEach { insert(db, it) }
    }

    override fun onOpen(db: SQLiteDatabase) {
        // onCreate has committed by now, so the legacy rows are safely in the table.
        legacyFile?.takeIf { it.exists() }?.let { it.renameTo(File(it.path + ".imported")) }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Never drop the feedback table.
        if (oldVersion < 2) createPlayHistory(db)
        if (oldVersion < 3) createChannelAirings(db)
    }

    /** Version 3. */
    private fun createChannelAirings(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE channel_airings (
                channel_id TEXT PRIMARY KEY NOT NULL,
                track_hash TEXT NOT NULL,
                duration_ms INTEGER NOT NULL,
                started_at_ms INTEGER NOT NULL
            ) WITHOUT ROWID
            """.trimIndent(),
        )
    }

    /** Version 2. */
    private fun createPlayHistory(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE play_history (
                track_hash TEXT PRIMARY KEY NOT NULL,
                last_played INTEGER NOT NULL
            ) WITHOUT ROWID
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE track_hashes (
                uri TEXT PRIMARY KEY NOT NULL,
                size_bytes INTEGER NOT NULL,
                modified_sec INTEGER NOT NULL,
                track_hash TEXT NOT NULL
            ) WITHOUT ROWID
            """.trimIndent(),
        )
    }

    /** The thumbs log (#13). */
    val feedbackLog: FeedbackLog = object : FeedbackLog {
        override fun append(event: FeedbackEvent) = insert(writableDatabase, event)

        override fun readAll(): List<FeedbackEvent> =
            readableDatabase.query(
                "feedback",
                arrayOf("track_hash", "thumb", "at_ms", "utc_offset_minutes", "channel_id"),
                null, null, null, null, "id",
            ).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val thumb = Thumb.entries.firstOrNull { it.name == c.getString(1) } ?: continue
                        add(FeedbackEvent(c.getString(0), thumb, c.getLong(2), c.getInt(3), c.getString(4)))
                    }
                }
            }
    }

    // REPLACE rather than UPSERT below: API 26 ships SQLite 3.18, before ON CONFLICT DO UPDATE.

    val playHistory: PlayHistoryStore = object : PlayHistoryStore {
        override fun readAll(): Map<String, Long> =
            readableDatabase.query("play_history", arrayOf("track_hash", "last_played"), null, null, null, null, null)
                .use { c -> buildMap { while (c.moveToNext()) put(c.getString(0), c.getLong(1)) } }

        override fun put(trackHash: String, lastPlayedMs: Long) {
            val values = ContentValues().apply {
                put("track_hash", trackHash)
                put("last_played", lastPlayedMs)
            }
            writableDatabase.insertWithOnConflict("play_history", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    val trackHashes: TrackHashStore = object : TrackHashStore {
        override fun readAll(): List<HashedFile> =
            readableDatabase.query(
                "track_hashes",
                arrayOf("uri", "size_bytes", "modified_sec", "track_hash"),
                null, null, null, null, null,
            ).use { c ->
                buildList { while (c.moveToNext()) add(HashedFile(c.getString(0), c.getLong(1), c.getLong(2), c.getString(3))) }
            }

        override fun put(file: HashedFile) {
            val values = ContentValues().apply {
                put("uri", file.uri)
                put("size_bytes", file.sizeBytes)
                put("modified_sec", file.modifiedSec)
                put("track_hash", file.trackHash)
            }
            writableDatabase.insertWithOnConflict("track_hashes", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    /** What each song channel last aired, for live-radio resume. */
    val channelAirings: ChannelAiringStore = object : ChannelAiringStore {
        override fun readAll(): List<ChannelAiring> =
            readableDatabase.query(
                "channel_airings",
                arrayOf("channel_id", "track_hash", "duration_ms", "started_at_ms"),
                null, null, null, null, null,
            ).use { c ->
                buildList { while (c.moveToNext()) add(ChannelAiring(c.getString(0), c.getString(1), c.getLong(2), c.getLong(3))) }
            }

        override fun put(airing: ChannelAiring) {
            val values = ContentValues().apply {
                put("channel_id", airing.channelId)
                put("track_hash", airing.trackHash)
                put("duration_ms", airing.durationMs)
                put("started_at_ms", airing.startedAtMs)
            }
            writableDatabase.insertWithOnConflict("channel_airings", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    private fun insert(db: SQLiteDatabase, e: FeedbackEvent) {
        val values = ContentValues().apply {
            put("track_hash", e.trackHash)
            put("thumb", e.thumb.name)
            put("at_ms", e.atMs)
            put("utc_offset_minutes", e.utcOffsetMinutes)
            put("channel_id", e.channelId)
        }
        db.insertOrThrow("feedback", null, values)
    }

    private companion object {
        const val DB_NAME = "feedback.db"
        const val DB_VERSION = 3
    }
}
