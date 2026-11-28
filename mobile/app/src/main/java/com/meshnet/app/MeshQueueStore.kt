package com.meshnet.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest

/** Bounded durable outbox and persistent receiver replay cache. */
data class QuarantinedOutboxFrame(
    val frameId: String,
    val frame: ByteArray,
    val messageId: String?,
    val frameKind: String,
    val createdAt: Long,
    val attempts: Int,
    val quarantinedAt: Long,
    val sourceVersion: Int,
    val reason: String
)

data class QuarantinedReplayId(
    val frameId: String,
    val createdAt: Long,
    val quarantinedAt: Long,
    val sourceVersion: Int,
    val reason: String
)

class MeshQueueStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext, "meshnet-outbox.db", null, 10
) {
    override fun onCreate(db: SQLiteDatabase) {
        createOutbox(db)
        createReceived(db)
        createDirectedTargets(db)
        createQuarantine(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // SQLiteOpenHelper runs onUpgrade inside one transaction. Archive incompatible
        // protocol state before removing it from the live replay/dispatch tables.
        createQuarantine(db)
        if (oldVersion < 2) createReceived(db)
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE outbox ADD COLUMN message_id TEXT")
            db.execSQL("ALTER TABLE outbox ADD COLUMN frame_kind TEXT NOT NULL DEFAULT 'MESSAGE'")
            db.execSQL("CREATE INDEX IF NOT EXISTS outbox_message_id ON outbox(message_id)")
        }
        if (oldVersion < 8) {
            // Protocol v6 changes the authenticated frame identity. Old frames and
            // replay markers must not be dispatched, but their original bytes/evidence
            // are retained atomically for explicit recovery or export.
            quarantineLegacyProtocolState(db, oldVersion)
        }
        if (oldVersion < 9) createDirectedTargets(db)
    }

    private fun createQuarantine(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS outbox_quarantine (
                frame_id TEXT NOT NULL,
                frame BLOB NOT NULL,
                message_id TEXT,
                frame_kind TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                attempts INTEGER NOT NULL,
                quarantined_at INTEGER NOT NULL,
                source_version INTEGER NOT NULL,
                reason TEXT NOT NULL,
                PRIMARY KEY(frame_id, source_version)
            )""".trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS outbox_quarantine_order ON outbox_quarantine(quarantined_at, created_at)"
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS received_ids_quarantine (
                frame_id TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                quarantined_at INTEGER NOT NULL,
                source_version INTEGER NOT NULL,
                reason TEXT NOT NULL,
                PRIMARY KEY(frame_id, source_version)
            )""".trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS received_ids_quarantine_order ON received_ids_quarantine(quarantined_at, created_at)"
        )
    }

    private fun quarantineLegacyProtocolState(db: SQLiteDatabase, sourceVersion: Int) {
        val quarantinedAt = System.currentTimeMillis()
        val reason = "Legacy protocol state is incompatible with the authenticated v6 frame envelope; retained and not replayed."
        db.execSQL(
            """INSERT OR IGNORE INTO outbox_quarantine(
                frame_id, frame, message_id, frame_kind, created_at, attempts,
                quarantined_at, source_version, reason
            )
            SELECT frame_id, frame, message_id, frame_kind, created_at, attempts, ?, ?, ?
            FROM outbox""".trimIndent(),
            arrayOf<Any>(quarantinedAt, sourceVersion, reason)
        )
        db.execSQL(
            """INSERT OR IGNORE INTO received_ids_quarantine(
                frame_id, created_at, quarantined_at, source_version, reason
            )
            SELECT frame_id, created_at, ?, ?, ?
            FROM received_ids""".trimIndent(),
            arrayOf<Any>(quarantinedAt, sourceVersion, reason)
        )
        db.delete("outbox", null, null)
        db.delete("received_ids", null, null)
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

    private fun createDirectedTargets(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS directed_targets (
                message_id TEXT PRIMARY KEY NOT NULL,
                destination TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS directed_targets_created_at ON directed_targets(created_at)")
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

    /**
     * Frames older than the protocol validity window cannot be accepted by a receiver.
     * Preserve their exact bytes and metadata in quarantine before removing them from the
     * dispatch queue, so stale records neither loop forever nor permanently consume capacity.
     */
    private fun quarantineExpiredOutbox(db: SQLiteDatabase, now: Long = System.currentTimeMillis()): Int {
        val cutoff = now - MAX_AGE_MS
        val quarantinedAt = now
        val reason = "Outbound frame exceeded the 30-day protocol validity window; original bytes retained and not replayed."
        db.beginTransaction()
        try {
            db.execSQL(
                """INSERT OR IGNORE INTO outbox_quarantine(
                    frame_id, frame, message_id, frame_kind, created_at, attempts,
                    quarantined_at, source_version, reason
                )
                SELECT frame_id, frame, message_id, frame_kind, created_at, attempts, ?, ?, ?
                FROM outbox WHERE created_at < ?""".trimIndent(),
                arrayOf<Any>(quarantinedAt, MeshFrame.PROTOCOL_VERSION, reason, cutoff)
            )
            val removed = db.delete("outbox", "created_at < ?", arrayOf(cutoff.toString()))
            db.setTransactionSuccessful()
            return removed
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun enqueue(frame: ByteArray): Boolean {
        if (frame.isEmpty() || frame.size > MeshFrame.MAX_FRAME_BYTES) return false
        val now = System.currentTimeMillis()
        val decoded = try { MeshFrame.decode(frame) } catch (_: Exception) { return false }
        val id = digest(frame)
        val db = writableDatabase
        quarantineExpiredOutbox(db, now)
        if (db.rawQuery("SELECT 1 FROM outbox WHERE frame_id = ?", arrayOf(id)).use { it.moveToFirst() }) {
            return true
        }
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
        db.beginTransaction()
        try {
            val current = db.rawQuery("SELECT COUNT(*) FROM outbox", null).use {
                if (it.moveToFirst()) it.getInt(0) else MAX_ENTRIES
            }
            // Backpressure is safer than evicting an unacknowledged message without notice.
            if (current >= MAX_ENTRIES) return false
            val inserted = db.insertWithOnConflict("outbox", null, values, SQLiteDatabase.CONFLICT_IGNORE)
            if (inserted == -1L) {
                return db.rawQuery("SELECT 1 FROM outbox WHERE frame_id = ?", arrayOf(id))
                    .use { it.moveToFirst() }
            }
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Returns preserved legacy frames for explicit recovery/export. These records are
     * deliberately excluded from pending() and are never replayed automatically.
     */
    @Synchronized
    fun quarantinedFrames(limit: Int = MAX_ENTRIES): List<QuarantinedOutboxFrame> {
        if (limit <= 0) return emptyList()
        return writableDatabase.rawQuery(
            """SELECT frame_id, frame, message_id, frame_kind, created_at, attempts,
                      quarantined_at, source_version, reason
               FROM outbox_quarantine
               ORDER BY quarantined_at ASC, created_at ASC
               LIMIT ?""".trimIndent(),
            arrayOf(limit.coerceAtMost(MAX_QUARANTINED_READ_LIMIT).toString())
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        QuarantinedOutboxFrame(
                            frameId = cursor.getString(0),
                            frame = cursor.getBlob(1),
                            messageId = cursor.getString(2),
                            frameKind = cursor.getString(3),
                            createdAt = cursor.getLong(4),
                            attempts = cursor.getInt(5),
                            quarantinedAt = cursor.getLong(6),
                            sourceVersion = cursor.getInt(7),
                            reason = cursor.getString(8)
                        )
                    )
                }
            }
        }
    }

    /** Returns archived replay markers for diagnostics and explicit recovery tooling. */
    @Synchronized
    fun quarantinedReplayIds(limit: Int = MAX_RECEIVED_IDS): List<QuarantinedReplayId> {
        if (limit <= 0) return emptyList()
        return writableDatabase.rawQuery(
            """SELECT frame_id, created_at, quarantined_at, source_version, reason
               FROM received_ids_quarantine
               ORDER BY quarantined_at ASC, created_at ASC
               LIMIT ?""".trimIndent(),
            arrayOf(limit.coerceAtMost(MAX_QUARANTINED_READ_LIMIT).toString())
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        QuarantinedReplayId(
                            frameId = cursor.getString(0),
                            createdAt = cursor.getLong(1),
                            quarantinedAt = cursor.getLong(2),
                            sourceVersion = cursor.getInt(3),
                            reason = cursor.getString(4)
                        )
                    )
                }
            }
        }
    }

    @Synchronized
    fun canAcceptBatch(count: Int): Boolean {
        if (count < 0 || count > MAX_ENTRIES) return false
        val db = writableDatabase
        quarantineExpiredOutbox(db)
        val current = db.rawQuery("SELECT COUNT(*) FROM outbox", null).use {
            if (it.moveToFirst()) it.getInt(0) else MAX_ENTRIES
        }
        return current + count <= MAX_ENTRIES
    }

    @Synchronized
    fun pending(limit: Int = MAX_ENTRIES): List<ByteArray> {
        val db = writableDatabase
        quarantineExpiredOutbox(db)
        return db.rawQuery(
            "SELECT frame FROM outbox ORDER BY created_at ASC LIMIT ?",
            arrayOf(limit.coerceIn(1, MAX_ENTRIES).toString())
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getBlob(0)) } }
    }

    /** Removes a locally originated directed message only after its authenticated destination ACK is accepted. */
    @Synchronized
    fun acknowledge(messageId: String): Int {
        if (messageId.isBlank()) return 0
        val removed = writableDatabase.delete(
            "outbox", "message_id = ? AND frame_kind = ?",
            arrayOf(messageId, MeshFrame.KIND_MESSAGE)
        )
        val targetRemoved = forgetDestination(messageId)
        return maxOf(removed, targetRemoved)
    }


    @Synchronized
    fun recordDestination(messageId: String, destination: String) {
        if (messageId.isBlank() || !destination.matches(Regex("[A-Za-z0-9_-]{1,32}"))) return
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.delete("directed_targets", "created_at < ?", arrayOf((now - MAX_AGE_MS).toString()))
        val values = ContentValues().apply {
            put("message_id", messageId)
            put("destination", destination)
            put("created_at", now)
        }
        db.insertWithOnConflict("directed_targets", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        db.execSQL(
            "DELETE FROM directed_targets WHERE message_id IN (SELECT message_id FROM directed_targets ORDER BY created_at ASC LIMIT MAX(0, (SELECT COUNT(*) FROM directed_targets) - ?))",
            arrayOf(MAX_ENTRIES)
        )
    }

    @Synchronized
    fun forgetDestination(messageId: String): Int =
        writableDatabase.delete("directed_targets", "message_id = ?", arrayOf(messageId))

    /**
     * A directed message can only be retired by its intended destination.
     *
     * Group broadcasts fail closed until a cryptographically authenticated, durable
     * membership roster and per-member receipt ledger exist. A shared group key proves
     * group-key possession, not that every intended member received the message.
     */
    @Synchronized
    fun receiptMatches(messageId: String, receiptOrigin: String): Boolean {
        if (messageId.isBlank() || receiptOrigin.isBlank()) return false
        val db = writableDatabase
        quarantineExpiredOutbox(db)
        db.delete("directed_targets", "created_at < ?", arrayOf((System.currentTimeMillis() - MAX_AGE_MS).toString()))
        val recordedDestination = db.rawQuery(
            "SELECT destination FROM directed_targets WHERE message_id = ? LIMIT 1",
            arrayOf(messageId)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        if (recordedDestination != null) return recordedDestination == receiptOrigin

        val encoded = db.rawQuery(
            "SELECT frame FROM outbox WHERE message_id = ? AND frame_kind = ? LIMIT 1",
            arrayOf(messageId, MeshFrame.KIND_MESSAGE)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getBlob(0) else null } ?: return false
        val frame = runCatching { MeshFrame.decode(encoded) }.getOrNull() ?: return false
        val destination = frame.destination ?: return false
        return destination == receiptOrigin
    }

    /**
     * ACKs have no ACK-of-ACK protocol. Retire an ACK after at least one connected peer's
     * BLE queue accepts it; a later duplicate message can regenerate the same logical receipt.
     * MESSAGE frames remain durable until an authenticated group receipt arrives.
     */
    @Synchronized
    fun retireAcknowledgement(frame: ByteArray): Boolean {
        val decoded = try { MeshFrame.decode(frame) } catch (_: Exception) { return false }
        if (decoded.kind != MeshFrame.KIND_ACK) return false
        // All receivers derive the same logical ACK ID; a retry may have a different nonce
        // or route, so retire every queued representation of that logical receipt.
        return writableDatabase.delete(
            "outbox", "message_id = ? AND frame_kind = ?",
            arrayOf(decoded.id, MeshFrame.KIND_ACK)
        ) > 0
    }

    @Synchronized
    fun containsFrame(frame: ByteArray): Boolean {
        return writableDatabase.rawQuery(
            "SELECT 1 FROM outbox WHERE frame_id = ?", arrayOf(digest(frame))
        ).use { it.moveToFirst() }
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
            db.delete("directed_targets", null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    companion object {
        private const val MAX_ENTRIES = 6000
        private const val MAX_RECEIVED_IDS = 8192
        private const val MAX_QUARANTINED_READ_LIMIT = 50_000
        private const val MAX_AGE_MS = 30L * 24L * 60L * 60L * 1000L
    }
}
