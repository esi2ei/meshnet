package com.meshnet.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest

/**
 * Bounded durable outbox and receiver replay cache.
 * No row is removed from the outbox merely because a local GATT write was queued:
 * the protocol has no end-to-end delivery acknowledgement yet.
 */
class MeshQueueStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext, "meshnet-outbox.db", null, 2
) {
    override fun onCreate(db: SQLiteDatabase) {
        createOutbox(db)
        createReceived(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createReceived(db)
    }

    private fun createOutbox(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS outbox (
                frame_id TEXT PRIMARY KEY NOT NULL,
                frame BLOB NOT NULL,
                created_at INTEGER NOT NULL,
                attempts INTEGER NOT NULL DEFAULT 0
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS outbox_created_at ON outbox(created_at)")
    }

    private fun createReceived(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS received_ids (
                frame_id TEXT PRIMARY KEY NOT NULL,
                created_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS received_created_at ON received_ids(created_at)")
    }

    @Synchronized
    fun enqueue(frame: ByteArray): Boolean {
        if (frame.isEmpty() || frame.size > MeshFrame.MAX_FRAME_BYTES) return false
        val now = System.currentTimeMillis()
        val db = writableDatabase
        db.delete("outbox", "created_at < ?", arrayOf((now - MAX_AGE_MS).toString()))
        val id = digest(frame)
        val values = ContentValues().apply {
            put("frame_id", id)
            put("frame", frame)
            put("created_at", now)
            put("attempts", 0)
        }
        val inserted = db.insertWithOnConflict("outbox", null, values, SQLiteDatabase.CONFLICT_IGNORE)
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
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getBlob(0)) } }
    }

    @Synchronized
    fun noteReplay(frame: ByteArray) {
        writableDatabase.execSQL(
            "UPDATE outbox SET attempts = attempts + 1 WHERE frame_id = ?",
            arrayOf(digest(frame))
        )
    }

    /** Returns true if this authenticated ID was already persisted. */
    @Synchronized
    fun markReceived(frameId: String, createdAt: Long): Boolean {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.delete("received_ids", "created_at < ?", arrayOf((now - MAX_AGE_MS).toString()))
        val values = ContentValues().apply {
            put("frame_id", frameId)
            put("created_at", createdAt)
        }
        val inserted = db.insertWithOnConflict(
            "received_ids", null, values, SQLiteDatabase.CONFLICT_IGNORE
        )
        db.execSQL(
            "DELETE FROM received_ids WHERE frame_id IN (SELECT frame_id FROM received_ids ORDER BY created_at DESC LIMIT -1 OFFSET ?)",
            arrayOf(MAX_RECEIVED_IDS)
        )
        return inserted == -1L
    }

    /** Prevents frames encrypted for a previous group key from being replayed after key rotation. */
    @Synchronized
    fun clearAll() {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("outbox", null, null)
            writableDatabase.delete("received_ids", null, null)
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    companion object {
        private const val MAX_ENTRIES = 500
        private const val MAX_RECEIVED_IDS = 4096
        private const val MAX_AGE_MS = 24L * 60L * 60L * 1000L
    }
}
