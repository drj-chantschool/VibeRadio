package app.viberadio.feedback

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

/**
 * The feedback log as a SQLite table (`feedback.db`), one row per press. The only Android code
 * in `feedback`; everything else is pure JVM.
 *
 * Append-only is enforced by the schema: triggers abort any UPDATE or DELETE on the table, so a
 * later feature can't quietly rewrite history. Rows come back in insert order (`id`).
 *
 * On first creation it imports [legacyFile] (the tab-separated log earlier builds wrote) in the
 * same transaction, then renames that file to `*.imported` so it's never read again.
 */
class SqliteFeedbackLog(
    context: Context,
    private val legacyFile: File? = null,
) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION), FeedbackLog {

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
        legacyFile?.let { LegacyTsvLog.read(it) }?.forEach { insert(db, it) }
    }

    override fun onOpen(db: SQLiteDatabase) {
        // onCreate has committed by now, so the legacy rows are safely in the table.
        legacyFile?.takeIf { it.exists() }?.let { it.renameTo(File(it.path + ".imported")) }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 is the first schema. Future versions migrate here; never drop the table.
    }

    override fun append(event: FeedbackEvent) {
        insert(writableDatabase, event)
    }

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
        const val DB_VERSION = 1
    }
}
