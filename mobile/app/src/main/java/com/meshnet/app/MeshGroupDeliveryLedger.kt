package com.meshnet.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

internal data class MeshDeliveryRecipient(val memberId: String, val publicKey: String)

internal data class MeshGroupDeliverySnapshot(
    val messageId: String,
    val groupId: String,
    val senderId: String,
    val senderPublicKey: String,
    val rosterDigest: String,
    val recipients: List<MeshDeliveryRecipient>,
    val createdAt: Long,
    val signature: String
) {
    fun canonicalPayload(): ByteArray = listOf(
        "meshnet-group-snapshot-v1",
        messageId,
        groupId,
        senderId,
        senderPublicKey,
        rosterDigest,
        recipients.sortedBy { it.memberId }.joinToString(",") { "${it.memberId}:${it.publicKey}" },
        createdAt.toString()
    ).joinToString("|").toByteArray(Charsets.UTF_8)

    val digest: String
        get() = sha256(canonicalPayload() + "|$signature".toByteArray(Charsets.UTF_8))

    fun verify(): Boolean {
        if (!messageId.matches(ID_PATTERN) || !groupId.matches(HEX_64)) return false
        if (!senderId.matches(NODE_PATTERN) || !senderPublicKey.matches(KEY_PATTERN)) return false
        if (!rosterDigest.matches(HEX_64) || createdAt <= 0L) return false
        if (recipients.isEmpty() || recipients.size > MAX_RECIPIENTS) return false
        if (recipients.map { it.memberId }.distinct().size != recipients.size) return false
        if (recipients.any {
                !it.memberId.matches(NODE_PATTERN) || !it.publicKey.matches(KEY_PATTERN) ||
                    !MeshDeviceIdentity.verifyPublicKeyBinding(it.publicKey, it.memberId)
            }) return false
        if (senderId in recipients.map { it.memberId }) return false
        return MeshDeviceIdentity.verifyDetached(
            senderPublicKey, senderId, SNAPSHOT_DOMAIN, canonicalPayload(), signature
        )
    }

    fun encode(): String = JSONObject()
        .put("messageId", messageId)
        .put("groupId", groupId)
        .put("senderId", senderId)
        .put("senderPublicKey", senderPublicKey)
        .put("rosterDigest", rosterDigest)
        .put("recipients", JSONArray().apply {
            recipients.sortedBy { it.memberId }.forEach { recipient ->
                put(JSONObject().put("memberId", recipient.memberId).put("publicKey", recipient.publicKey))
            }
        })
        .put("createdAt", createdAt)
        .put("signature", signature)
        .toString()

    companion object {
        private const val SNAPSHOT_DOMAIN = "meshnet.group.snapshot.v1"
        private const val ID_PATTERN = "[A-Za-z0-9_-]{1,64}"
        private const val NODE_PATTERN = "[A-Za-z0-9_-]{20,32}"
        private const val KEY_PATTERN = "[A-Za-z0-9_-]{80,160}"
        private const val HEX_64 = "[a-f0-9]{64}"
        private const val MAX_RECIPIENTS = 512

        fun create(
            identity: MeshDeviceIdentity,
            messageId: String,
            groupId: String,
            observedEvents: List<MeshMembershipEvent>,
            createdAt: Long = System.currentTimeMillis()
        ): MeshGroupDeliverySnapshot {
            require(messageId.matches(ID_PATTERN) && groupId.matches(HEX_64))
            require(createdAt > 0L)
            require(observedEvents.isNotEmpty() && observedEvents.size <= MAX_RECIPIENTS * 2)
            require(observedEvents.all { it.groupId == groupId && it.verify() }) {
                "Snapshot requires verified membership events for this group"
            }
            val observedRoster = observedEvents.groupBy { it.memberId }.map { (memberId, memberEvents) ->
                val keyConflict = memberEvents.map { it.publicKey }.distinct().size != 1
                val byCounter = memberEvents.groupBy { it.counter }.toSortedMap()
                val counterConflict = byCounter.values.any { it.map { event -> event.digest }.distinct().size > 1 }
                val ordered = byCounter.values.mapNotNull { it.singleOrNull() }
                val hasGap = byCounter.keys.withIndex().any { (index, value) -> value != index + 1L }
                val status = when {
                    keyConflict || counterConflict -> "CONFLICTED"
                    hasGap || ordered.isEmpty() || ordered.first().type != MeshMembershipEvent.JOIN -> "INCOMPLETE"
                    ordered.last().type == MeshMembershipEvent.JOIN -> "ACTIVE"
                    else -> "LEFT"
                }
                MeshMembershipState(memberId, memberEvents.first().publicKey, status, byCounter.keys.maxOrNull() ?: 0L)
            }
            require(observedRoster.size <= MAX_RECIPIENTS * 2)
            require(observedRoster.all { it.status == "ACTIVE" || it.status == "LEFT" }) {
                "Cannot snapshot an incomplete or conflicted local roster"
            }
            require(observedRoster.any {
                it.memberId == identity.nodeId && it.publicKey == identity.publicKey && it.status == "ACTIVE"
            }) { "Sender must be an active member in the verified local roster" }
            val orderedRoster = observedRoster.sortedBy { it.memberId }
            val rosterCanonical = orderedRoster.joinToString("|") {
                "${it.memberId}:${it.publicKey}:${it.status}:${it.lastCounter}"
            }
            val recipients = orderedRoster
                .filter { it.status == "ACTIVE" && it.memberId != identity.nodeId }
                .map { MeshDeliveryRecipient(it.memberId, it.publicKey) }
            require(recipients.isNotEmpty() && recipients.size <= MAX_RECIPIENTS) {
                "No eligible remote recipients in the observed roster"
            }
            val unsigned = MeshGroupDeliverySnapshot(
                messageId, groupId, identity.nodeId, identity.publicKey,
                sha256(rosterCanonical.toByteArray(Charsets.UTF_8)),
                recipients, createdAt, ""
            )
            return unsigned.copy(
                signature = identity.signDetached(SNAPSHOT_DOMAIN, unsigned.canonicalPayload())
            )
        }

        fun decode(encoded: String): MeshGroupDeliverySnapshot {
            require(encoded.toByteArray(Charsets.UTF_8).size <= 128 * 1024)
            val json = JSONObject(encoded)
            val array = json.getJSONArray("recipients")
            require(array.length() in 1..MAX_RECIPIENTS)
            val recipients = buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    add(MeshDeliveryRecipient(item.getString("memberId"), item.getString("publicKey")))
                }
            }
            return MeshGroupDeliverySnapshot(
                json.getString("messageId"), json.getString("groupId"),
                json.getString("senderId"), json.getString("senderPublicKey"),
                json.getString("rosterDigest"), recipients,
                json.getLong("createdAt"), json.getString("signature")
            )
        }
    }
}

internal data class MeshMemberDeliveryReceipt(
    val messageId: String,
    val groupId: String,
    val memberId: String,
    val publicKey: String,
    val snapshotDigest: String,
    val receiptId: String,
    val type: String,
    val createdAt: Long,
    val signature: String
) {
    fun canonicalPayload(): ByteArray = listOf(
        "meshnet-member-receipt-v1", messageId, groupId, memberId,
        publicKey, snapshotDigest, receiptId, type, createdAt.toString()
    ).joinToString("|").toByteArray(Charsets.UTF_8)

    fun verify(): Boolean {
        if (!messageId.matches(ID_PATTERN) || !groupId.matches(HEX_64)) return false
        if (!memberId.matches(NODE_PATTERN) || !publicKey.matches(KEY_PATTERN)) return false
        if (!snapshotDigest.matches(HEX_64) || !receiptId.matches(ID_PATTERN)) return false
        if (type !in TYPES || createdAt <= 0L) return false
        if (createdAt > System.currentTimeMillis() + MAX_CLOCK_SKEW_MS) return false
        return MeshDeviceIdentity.verifyDetached(
            publicKey, memberId, RECEIPT_DOMAIN, canonicalPayload(), signature
        )
    }

    fun encode(): String = JSONObject()
        .put("messageId", messageId).put("groupId", groupId)
        .put("memberId", memberId).put("publicKey", publicKey)
        .put("snapshotDigest", snapshotDigest).put("receiptId", receiptId)
        .put("type", type).put("createdAt", createdAt).put("signature", signature)
        .toString()

    companion object {
        const val ACCEPTED = "ACCEPTED"
        const val DELIVERED = "DELIVERED"
        const val READ = "READ"
        private val TYPES = setOf(ACCEPTED, DELIVERED, READ)
        private const val RECEIPT_DOMAIN = "meshnet.group.receipt.v1"
        private const val ID_PATTERN = "[A-Za-z0-9_-]{1,64}"
        private const val NODE_PATTERN = "[A-Za-z0-9_-]{20,32}"
        private const val KEY_PATTERN = "[A-Za-z0-9_-]{80,160}"
        private const val HEX_64 = "[a-f0-9]{64}"
        private const val MAX_CLOCK_SKEW_MS = 5L * 60L * 1000L

        fun create(
            identity: MeshDeviceIdentity,
            snapshot: MeshGroupDeliverySnapshot,
            type: String,
            createdAt: Long = System.currentTimeMillis(),
            receiptId: String = UUID.randomUUID().toString()
        ): MeshMemberDeliveryReceipt {
            require(snapshot.verify() && identity.nodeId in snapshot.recipients.map { it.memberId })
            require(type in TYPES && createdAt > 0L)
            val unsigned = MeshMemberDeliveryReceipt(
                snapshot.messageId, snapshot.groupId, identity.nodeId, identity.publicKey,
                snapshot.digest, receiptId, type, createdAt, ""
            )
            return unsigned.copy(
                signature = identity.signDetached(RECEIPT_DOMAIN, unsigned.canonicalPayload())
            )
        }
    }
}

/**
 * Local immutable recipient snapshots and append-only per-member receipt evidence.
 * This ledger does not transmit records and never retires an outbox message by itself.
 */
internal class MeshGroupDeliveryLedger(context: Context) : SQLiteOpenHelper(
    context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE delivery_snapshots (
                message_id TEXT PRIMARY KEY NOT NULL,
                group_id TEXT NOT NULL,
                snapshot_digest TEXT NOT NULL,
                encoded TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE delivery_recipients (
                message_id TEXT NOT NULL,
                member_id TEXT NOT NULL,
                expected_public_key TEXT NOT NULL,
                highest_receipt_type TEXT,
                highest_receipt_at INTEGER,
                PRIMARY KEY(message_id, member_id)
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE delivery_receipt_events (
                message_id TEXT NOT NULL,
                member_id TEXT NOT NULL,
                receipt_id TEXT NOT NULL,
                encoded TEXT NOT NULL,
                received_at INTEGER NOT NULL,
                PRIMARY KEY(message_id, member_id, receipt_id)
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX delivery_receipts_message ON delivery_receipt_events(message_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun createSnapshot(snapshot: MeshGroupDeliverySnapshot): Boolean {
        require(snapshot.verify()) { "Invalid sender signature or recipient snapshot" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val existing = db.rawQuery(
                "SELECT encoded FROM delivery_snapshots WHERE message_id = ?",
                arrayOf(snapshot.messageId)
            ).use { if (it.moveToFirst()) it.getString(0) else null }
            if (existing != null) {
                require(existing == snapshot.encode()) { "Message ID already has a different immutable snapshot" }
                db.setTransactionSuccessful()
                return false
            }
            val snapshotCount = db.rawQuery("SELECT COUNT(*) FROM delivery_snapshots", null).use {
                if (it.moveToFirst()) it.getInt(0) else MAX_SNAPSHOTS
            }
            val recipientCount = db.rawQuery("SELECT COUNT(*) FROM delivery_recipients", null).use {
                if (it.moveToFirst()) it.getInt(0) else MAX_TOTAL_RECIPIENTS
            }
            require(snapshotCount < MAX_SNAPSHOTS &&
                recipientCount + snapshot.recipients.size <= MAX_TOTAL_RECIPIENTS) {
                "Delivery snapshot ledger is full; resolve or export pending delivery records"
            }
            db.insertOrThrow(
                "delivery_snapshots", null,
                ContentValues().apply {
                    put("message_id", snapshot.messageId)
                    put("group_id", snapshot.groupId)
                    put("snapshot_digest", snapshot.digest)
                    put("encoded", snapshot.encode())
                    put("created_at", snapshot.createdAt)
                }
            )
            snapshot.recipients.forEach { recipient ->
                db.insertOrThrow(
                    "delivery_recipients", null,
                    ContentValues().apply {
                        put("message_id", snapshot.messageId)
                        put("member_id", recipient.memberId)
                        put("expected_public_key", recipient.publicKey)
                    }
                )
            }
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun snapshot(messageId: String): MeshGroupDeliverySnapshot? =
        readableDatabase.rawQuery(
            "SELECT encoded FROM delivery_snapshots WHERE message_id = ?",
            arrayOf(messageId)
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else MeshGroupDeliverySnapshot.decode(cursor.getString(0))
                .also { require(it.verify()) { "Stored recipient snapshot signature is invalid" } }
        }

    /** Returns true only for a newly persisted, valid receipt event. */
    @Synchronized
    fun recordReceipt(receipt: MeshMemberDeliveryReceipt): Boolean {
        require(receipt.verify()) { "Invalid member receipt signature or identity binding" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val snapshotRow = db.rawQuery(
                "SELECT group_id, snapshot_digest FROM delivery_snapshots WHERE message_id = ?",
                arrayOf(receipt.messageId)
            ).use { cursor ->
                if (!cursor.moveToFirst()) null else cursor.getString(0) to cursor.getString(1)
            } ?: return false
            if (snapshotRow.first != receipt.groupId || snapshotRow.second != receipt.snapshotDigest) return false
            val expectedKey = db.rawQuery(
                "SELECT expected_public_key FROM delivery_recipients WHERE message_id = ? AND member_id = ?",
                arrayOf(receipt.messageId, receipt.memberId)
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                ?: return false
            if (expectedKey != receipt.publicKey) return false

            val existing = db.rawQuery(
                "SELECT encoded FROM delivery_receipt_events WHERE message_id = ? AND member_id = ? AND receipt_id = ?",
                arrayOf(receipt.messageId, receipt.memberId, receipt.receiptId)
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            if (existing != null) {
                require(existing == receipt.encode()) { "Receipt ID equivocation detected" }
                db.setTransactionSuccessful()
                return false
            }
            val receiptCount = db.rawQuery("SELECT COUNT(*) FROM delivery_receipt_events", null).use {
                if (it.moveToFirst()) it.getInt(0) else MAX_RECEIPT_EVENTS
            }
            require(receiptCount < MAX_RECEIPT_EVENTS) {
                "Delivery receipt ledger is full; export or reconcile before accepting more receipts"
            }
            db.insertOrThrow(
                "delivery_receipt_events", null,
                ContentValues().apply {
                    put("message_id", receipt.messageId)
                    put("member_id", receipt.memberId)
                    put("receipt_id", receipt.receiptId)
                    put("encoded", receipt.encode())
                    put("received_at", System.currentTimeMillis())
                }
            )
            val current = db.rawQuery(
                "SELECT highest_receipt_type FROM delivery_recipients WHERE message_id = ? AND member_id = ?",
                arrayOf(receipt.messageId, receipt.memberId)
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            if (rank(receipt.type) > rank(current)) {
                db.update(
                    "delivery_recipients",
                    ContentValues().apply {
                        put("highest_receipt_type", receipt.type)
                        put("highest_receipt_at", receipt.createdAt)
                    },
                    "message_id = ? AND member_id = ?",
                    arrayOf(receipt.messageId, receipt.memberId)
                )
            }
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun progress(messageId: String): Pair<Int, Int> =
        readableDatabase.rawQuery(
            "SELECT COUNT(*), SUM(CASE WHEN highest_receipt_type IN ('DELIVERED','READ') THEN 1 ELSE 0 END) FROM delivery_recipients WHERE message_id = ?",
            arrayOf(messageId)
        ).use { cursor ->
            if (!cursor.moveToFirst()) 0 to 0
            else cursor.getInt(1) to cursor.getInt(0)
        }

    @Synchronized
    fun allDelivered(messageId: String): Boolean {
        val (delivered, total) = progress(messageId)
        return total > 0 && delivered == total
    }

    @Synchronized
    fun receiptCount(messageId: String): Int =
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM delivery_receipt_events WHERE message_id = ?",
            arrayOf(messageId)
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    private fun rank(type: String?): Int = when (type) {
        MeshMemberDeliveryReceipt.ACCEPTED -> 1
        MeshMemberDeliveryReceipt.DELIVERED -> 2
        MeshMemberDeliveryReceipt.READ -> 3
        else -> 0
    }

    companion object {
        private const val DATABASE_NAME = "meshnet-group-delivery.db"
        private const val DATABASE_VERSION = 1
        private const val MAX_SNAPSHOTS = 6000
        private const val MAX_TOTAL_RECIPIENTS = 250000
        private const val MAX_RECEIPT_EVENTS = 250000
        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
