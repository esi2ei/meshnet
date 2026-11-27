package com.meshnet.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest

/** Bounded durable outbox and persistent receiver replay cache. */
class MeshQueueStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext, "meshnet-outbox.db", null, 4
) {
    override fun onCreate(db: SQLiteDatabase) {
        createOutbox(db)
        createReceived(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createReceived(db)
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE outbox ADD COLUMN message_id TEXT")
            db.execSQL("ALTER TABLE outbox ADD COLUMN frame_kind TEXT NOT NULL DEFAULT 'MESSAGE'")
            db.execSQL("CREATE INDEX IF NOT EXISTS outbox_message_id ON outbox(message_id)")
        }
        if (oldVersion < 4) {
            // Protocol v2 changes the authenticated-data schema; v1 frames cannot be replayed safely.
            db.delete("outbox", null, null)
            db.delete("received_ids", null, null)
        }
    }

    private fun createOutbox(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS outbox (
                frame_id TEXT PRIMARY KEY NOT NULL,
                frame BLOB NOT NULL,
                message_id TEXT,
                frame_kind TEXT NOT NULL DEFAULT 'MESSAGE',
                created_at INTEGER NOT NULL,
                attempts INTEGER NOT NULL DEFAULT 0
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS outbox_created_at ON outbox(created_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS outbox_message_id ON outbox(message_id)")
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
        val decoded = try { MeshFrame.decode(frame) } catch (_: Exception) { return false }
        val id = digest(frame)
        if (decoded.kind == MeshFrame.KIND_ACK) {
            val pendingAck = db.rawQuery(
                "SELECT 1 FROM outbox WHERE message_id = ? AND frame_kind = ?",
                arrayOf(decoded.id, MeshFrame.KIND_ACK)
            ).use { it.moveToFirst() }
            if (pendingAck) return true
        }
        val values = ContentValues().apply {
            put("frame_id", id)
            put("frame", frame)
            put("message_id", decoded.id)
            put("frame_kind", decoded.kind)
            put("created_at", now)
            put("attempts", 0)
        }
        db.insertWithOnConflict("outbox", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        db.execSQL(
            "DELETE FROM outbox WHERE frame_id IN (SELECT frame_id FROM outbox ORDER BY created_at ASC LIMIT MAX(0, (SELECT COUNT(*) FROM outbox) - ?))",
            arrayOf(MAX_ENTRIES)
        )
        return db.rawQuery(
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

    /** Removes a locally originated message only after an authenticated group receipt arrives. */
    @Synchronized
    fun acknowledge(messageId: String): Int {
        if (messageId.isBlank()) return 0
        return writableDatabase.delete(
            "outbox", "message_id = ? AND frame_kind = ?",
            arrayOf(messageId, MeshFrame.KIND_MESSAGE)
        )
    }

    @Synchronized
    fun noteReplay(frame: ByteArray) {
        writableDatabase.execSQL(
            "UPDATE outbox SET attempts = attempts + 1 WHERE frame_id = ?",
            arrayOf(digest(frame))
        )
    }

    @Synchronized
    fun hasReceived(frameId: String): Boolean {
        val db = writableDatabase
        db.delete("received_ids", "created_at < ?", arrayOf(
            (System.currentTimeMillis() - MAX_AGE_MS).toString()
        ))
        return db.rawQuery(
            "SELECT 1 FROM received_ids WHERE frame_id = ?", arrayOf(frameId)
        ).use { it.moveToFirst() }
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
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("outbox", null, null)
            db.delete("received_ids", null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
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
