package com.meshnet.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest

/**
 * Small durable best-effort outbox. Entries are retained until their TTL expires because
 * this prototype has no end-to-end delivery acknowledgement. Replays are safe only because
 * receivers deduplicate authenticated frame IDs.
 */
class MeshQueueStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext, "meshnet-outbox.db", null, 1
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE outbox (
                frame_id TEXT PRIMARY KEY NOT NULL,
                frame BLOB NOT NULL,
                created_at INTEGER NOT NULL,
                attempts INTEGER NOT NULL DEFAULT 0
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX outbox_created_at ON outbox(created_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // No released schema exists yet. Future migrations must preserve queued frames.
    }

    @Synchronized
    fun enqueue(frame: ByteArray): Boolean {
        if (frame.isEmpty() || frame.size > MeshFrame.MAX_FRAME_BYTES) return false
        val now = System.currentTimeMillis()
        val db = writableDatabase
        db.delete("outbox", "created_at < ?", arrayOf((now - MAX_AGE_MS).toString()))
        val id = MessageDigest.getInstance("SHA-256")
            .digest(frame).joinToString("") { "%02x".format(it) }
        val values = ContentValues().apply {
            put("frame_id", id)
            put("frame", frame)
            put("created_at", now)
            put("attempts", 0)
        }
        val inserted = db.insertWithOnConflict("outbox", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        // Bound disk usage. Oldest queued frames are discarded first at the hard cap.
        db.execSQL(
            "DELETE FROM outbox WHERE frame_id IN (SELECT frame_id FROM outbox ORDER BY created_at ASC LIMIT MAX(0, (SELECT COUNT(*) FROM outbox) - ?))",
            arrayOf(MAX_ENTRIES)
        )
        return inserted != -1L || db.rawQuery(
            "SELECT 1 FROM outbox WHERE frame_id = ?", arrayOf(id)
        ).use { it.moveToFirst() }
    }

    @Synchronized
    fun pending(limit: Int = MAX_ENTRIES): List<ByteArray> {
        val now = System.currentTimeMillis()
        val db = writableDatabase
        db.delete("outbox", "created_at < ?", arrayOf((now - MAX_AGE_MS).toString()))
        return db.rawQuery(
            "SELECT frame FROM outbox ORDER BY created_at ASC LIMIT ?",
            arrayOf(limit.coerceIn(1, MAX_ENTRIES).toString())
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getBlob(0))
            }
        }
    }

    @Synchronized
    fun noteReplay(frame: ByteArray) {
        val id = MessageDigest.getInstance("SHA-256")
            .digest(frame).joinToString("") { "%02x".format(it) }
        writableDatabase.execSQL(
            "UPDATE outbox SET attempts = attempts + 1 WHERE frame_id = ?", arrayOf(id)
        )
    }

    companion object {
        private const val MAX_ENTRIES = 500
        private const val MAX_AGE_MS = 24L * 60L * 60L * 1000L
    }
}
