package com.meshnet.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

private const val MEMBERSHIP_MAX_CLOCK_SKEW_MS = 5L * 60L * 1000L

/**
 * Self-authenticating membership event. A signature proves control of one device key only;
 * it does not prove that the signer is authorized by a group owner or that this roster is global.
 */
internal data class MeshMembershipEvent(
    val groupId: String,
    val eventId: String,
    val memberId: String,
    val publicKey: String,
    val counter: Long,
    val type: String,
    val createdAt: Long,
    val signature: String
) {
    fun canonicalPayload(): ByteArray =
        listOf(
            "meshnet-membership-event-v1", groupId, eventId, memberId,
            publicKey, counter.toString(), type, createdAt.toString()
        ).joinToString("|").toByteArray(Charsets.UTF_8)

    fun verify(): Boolean {
        if (!groupId.matches(Regex("[a-f0-9]{64}"))) return false
        if (!eventId.matches(Regex("[A-Za-z0-9_-]{16,64}"))) return false
        if (!memberId.matches(Regex("[A-Za-z0-9_-]{20,32}"))) return false
        if (!publicKey.matches(Regex("[A-Za-z0-9_-]{80,160}"))) return false
        if (counter <= 0L || createdAt <= 0L) return false
        if (createdAt > System.currentTimeMillis() + MEMBERSHIP_MAX_CLOCK_SKEW_MS) return false
        if (type != JOIN && type != LEAVE) return false
        return MeshDeviceIdentity.verifyDetached(
            publicKey, memberId, SIGNATURE_DOMAIN, canonicalPayload(), signature
        )
    }

    fun encode(): String = JSONObject()
        .put("groupId", groupId)
        .put("eventId", eventId)
        .put("memberId", memberId)
        .put("publicKey", publicKey)
        .put("counter", counter)
        .put("type", type)
        .put("createdAt", createdAt)
        .put("signature", signature)
        .toString()

    val digest: String
        get() = MessageDigest.getInstance("SHA-256")
            .digest(encode().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        const val JOIN = "JOIN"
        const val LEAVE = "LEAVE"
        private const val SIGNATURE_DOMAIN = "meshnet.group.membership.v1"

        fun create(
            identity: MeshDeviceIdentity,
            groupId: String,
            counter: Long,
            type: String,
            createdAt: Long = System.currentTimeMillis(),
            eventId: String = UUID.randomUUID().toString()
        ): MeshMembershipEvent {
            require(groupId.matches(Regex("[a-f0-9]{64}"))) { "Invalid group ID" }
            require(counter > 0L && createdAt > 0L) { "Invalid membership event counter or timestamp" }
            require(type == JOIN || type == LEAVE) { "Invalid membership event type" }
            require(eventId.matches(Regex("[A-Za-z0-9_-]{16,64}"))) { "Invalid membership event ID" }
            val unsigned = MeshMembershipEvent(
                groupId, eventId, identity.nodeId, identity.publicKey,
                counter, type, createdAt, ""
            )
            return unsigned.copy(
                signature = identity.signDetached(SIGNATURE_DOMAIN, unsigned.canonicalPayload())
            )
        }

        fun decode(encoded: String): MeshMembershipEvent {
            require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_EVENT_BYTES) {
                "Membership event exceeds size limit"
            }
            val json = JSONObject(encoded)
            return MeshMembershipEvent(
                groupId = json.getString("groupId"),
                eventId = json.getString("eventId"),
                memberId = json.getString("memberId"),
                publicKey = json.getString("publicKey"),
                counter = json.getLong("counter"),
                type = json.getString("type"),
                createdAt = json.getLong("createdAt"),
                signature = json.getString("signature")
            )
        }

        private const val MAX_EVENT_BYTES = 4096
    }
}

internal data class MeshMembershipState(
    val memberId: String,
    val publicKey: String,
    val status: String,
    val lastCounter: Long
)

/**
 * Durable append-only local replica of signed JOIN/LEAVE events.
 *
 * The log is mergeable across offline partitions, but a local replica is not a globally
 * authoritative roster. Counter gaps and equivocation are exposed rather than guessed away.
 */
internal class MeshGroupMembershipStore(
    context: Context,
    databaseName: String = DATABASE_NAME
) : SQLiteOpenHelper(
    context.applicationContext, databaseName, null, DATABASE_VERSION
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE membership_events (
                group_id TEXT NOT NULL,
                event_id TEXT NOT NULL,
                member_id TEXT NOT NULL,
                public_key TEXT NOT NULL,
                counter INTEGER NOT NULL,
                event_type TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                signature TEXT NOT NULL,
                encoded TEXT NOT NULL,
                PRIMARY KEY(group_id, event_id)
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX membership_member_order ON membership_events(group_id, member_id, counter)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /** Returns true for a new event and false for an exact idempotent retry. */
    @Synchronized
    fun append(event: MeshMembershipEvent): Boolean {
        require(event.verify()) { "Invalid membership event signature or identity binding" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val existing = db.rawQuery(
                "SELECT encoded FROM membership_events WHERE group_id = ? AND event_id = ?",
                arrayOf(event.groupId, event.eventId)
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            if (existing != null) {
                require(existing == event.encode()) { "Membership event ID equivocation detected" }
                db.setTransactionSuccessful()
                return false
            }
            val count = db.rawQuery(
                "SELECT COUNT(*) FROM membership_events WHERE group_id = ?",
                arrayOf(event.groupId)
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else MAX_EVENTS_PER_GROUP }
            val totalCount = db.rawQuery("SELECT COUNT(*) FROM membership_events", null).use {
                if (it.moveToFirst()) it.getInt(0) else MAX_TOTAL_EVENTS
            }
            require(count < MAX_EVENTS_PER_GROUP && totalCount < MAX_TOTAL_EVENTS) {
                "Membership log is full; export or reconcile before accepting more events"
            }
            db.insertOrThrow(
                "membership_events", null,
                ContentValues().apply {
                    put("group_id", event.groupId)
                    put("event_id", event.eventId)
                    put("member_id", event.memberId)
                    put("public_key", event.publicKey)
                    put("counter", event.counter)
                    put("event_type", event.type)
                    put("created_at", event.createdAt)
                    put("signature", event.signature)
                    put("encoded", event.encode())
                }
            )
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    /** Merge is intentionally per-event and idempotent; invalid entries fail closed. */
    @Synchronized
    fun merge(events: List<MeshMembershipEvent>): Int {
        require(events.size <= MAX_MERGE_BATCH) { "Membership merge batch is too large" }
        var inserted = 0
        events.forEach { if (append(it)) inserted++ }
        return inserted
    }

    @Synchronized
    fun events(groupId: String): List<MeshMembershipEvent> {
        require(groupId.matches(Regex("[a-f0-9]{64}")))
        return readableDatabase.rawQuery(
            "SELECT encoded FROM membership_events WHERE group_id = ? ORDER BY member_id, counter, event_id",
            arrayOf(groupId)
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val event = MeshMembershipEvent.decode(cursor.getString(0))
                    require(event.verify()) { "Stored membership event failed signature verification" }
                    add(event)
                }
            }
        }
    }

    /** Bounded chronological page for best-effort peer synchronization. */
    @Synchronized
    fun eventsForSync(groupId: String, limit: Int = 64): List<MeshMembershipEvent> {
        require(groupId.matches(Regex("[a-f0-9]{64}")))
        require(limit in 1..MAX_SYNC_PAGE)
        return readableDatabase.rawQuery(
            "SELECT encoded FROM membership_events WHERE group_id = ? ORDER BY rowid DESC LIMIT ?",
            arrayOf(groupId, limit.toString())
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val event = MeshMembershipEvent.decode(cursor.getString(0))
                    require(event.verify()) { "Stored membership event failed signature verification" }
                    add(event)
                }
            }.asReversed()
        }
    }

    @Synchronized
    fun eventCount(groupId: String): Int {
        require(groupId.matches(Regex("[a-f0-9]{64}")))
        return readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM membership_events WHERE group_id = ?",
            arrayOf(groupId)
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    /**
     * Computes only the locally observed state. Gaps and same-counter conflicts are not
     * resolved by timestamp; they remain INCOMPLETE or CONFLICTED until reconciled.
     */
    @Synchronized
    fun roster(groupId: String): List<MeshMembershipState> =
        events(groupId).groupBy { it.memberId }.map { (memberId, memberEvents) ->
            val keyConflict = memberEvents.map { it.publicKey }.distinct().size != 1
            val byCounter = memberEvents.groupBy { it.counter }.toSortedMap()
            val counterConflict = byCounter.values.any { it.map { event -> event.digest }.distinct().size > 1 }
            val ordered = byCounter.values.mapNotNull { it.singleOrNull() }
            val maxCounter = byCounter.keys.maxOrNull() ?: 0L
            val hasGap = byCounter.keys.sorted().withIndex().any { (index, value) -> value != index + 1L }
            val status = when {
                keyConflict || counterConflict -> "CONFLICTED"
                hasGap -> "INCOMPLETE"
                ordered.isEmpty() -> "INCOMPLETE"
                ordered.first().type != MeshMembershipEvent.JOIN -> "INCOMPLETE"
                ordered.last().type == MeshMembershipEvent.JOIN -> "ACTIVE"
                else -> "LEFT"
            }
            MeshMembershipState(memberId, memberEvents.first().publicKey, status, maxCounter)
        }.sortedBy { it.memberId }

    companion object {
        private const val DATABASE_NAME = "meshnet-group-membership.db"
        private const val DATABASE_VERSION = 1
        private const val MAX_EVENTS_PER_GROUP = 10000
        private const val MAX_TOTAL_EVENTS = 50000
        private const val MAX_MERGE_BATCH = 512
        private const val MAX_SYNC_PAGE = 64
    }
}
