# Group Membership and Delivery Protocol

Status: partial implementation in PR #16; end-to-end group delivery is not implemented  
Protocol target: frame-bound group delivery remains a protocol v7 gate; current frame format remains v6

## 1. Problem and safety invariant

A broadcast ACK must never delete a group message merely because one device possesses the shared group key. A group key proves access to the encrypted group, not membership completeness or delivery to every intended recipient.

The current implementation therefore fails closed for broadcasts. Until this protocol is implemented, broadcast messages can remain in the durable outbox. Directed messages retain destination-bound receipt handling.

The invariant for the next implementation is:

> A group message is complete only when every device in the immutable recipient snapshot committed with that message has a valid, identity-authenticated delivery receipt, or an explicit, user-visible terminal policy has been applied to that recipient.

No automatic timeout may silently reinterpret an offline recipient as delivered.

## 2. Membership identity and admission

Each installation has a stable P-256 device identity. The public key is the member identity; the node ID is its truncated SHA-256 fingerprint. The private key remains in Android Keystore.

A JOIN event must contain:
- group identifier (derived from the group credential using a domain-separated hash; never the passphrase itself);
- member node ID and canonical public key;
- monotonically increasing per-member event counter;
- event ID and creation timestamp;
- protocol version and event type;
- signature by the joining device identity.

The receiver verifies that the public key fingerprints to the claimed node ID, validates the signature and group identifier, and persists the event idempotently.

**Admission policy must be explicit.** The current app has a shared group passphrase but no owner/admin identity, invitation certificate, or approval UI. Therefore possession of the passphrase currently grants the same cryptographic capability to every participant. It is not safe to silently treat one peer as a privileged membership authority. The first implementation may only claim “devices admitted with the shared group credential,” not administrator-approved membership. A future owner-controlled invitation/revocation model requires an explicit group-owner key, QR/deep-link invitation and recovery UX.

A shared passphrase also permits a malicious member to create additional device identities (Sybil admission). This protocol does not claim Sybil resistance. Rate limits and visible join notifications are necessary mitigations, not cryptographic prevention.

## 3. Replicated membership log

Membership is represented as an append-only, signed event log, not a mutable list sent by an arbitrary peer.

- JOIN is signed by the joining device.
- LEAVE is signed by the member leaving.
- **Implemented local primitive (not yet transported):** `MeshMembershipEvent` uses a canonical, domain-separated P-256 signature tied to group ID, event ID, member ID, public key, counter, type and timestamp. `MeshGroupMembershipStore` persists verified events in an append-only SQLite table, deduplicates exact retries, retains same-counter equivocation, supports bounded idempotent merge, and projects `ACTIVE`, `LEFT`, `INCOMPLETE` or `CONFLICTED` local state. The journal is bounded to 10,000 events per group / 50,000 total.
- These records are not yet sent or reconciled over MeshNet transports. The current frame protocol remains v6; no protocol-v7 frame binding, automatic JOIN/LEAVE, group-ID derivation from the credential, roster freshness UX, or sender/recipient integration exists. The local SQLite journal is not encrypted at rest.
- Events are keyed by event ID and deduplicated.
- A member's counter must increase; conflicting events at the same counter are retained as equivocation evidence and must not be resolved by “last write wins.”
- Roster state is a deterministic projection of verified events.
- Roster events are persisted before they are relayed or acknowledged.
- Offline peers exchange missing event IDs / bounded log pages when a link becomes available.
- Events are retained for the group lifetime; expiry must not silently erase membership history.
- A device that has been offline cannot be assumed removed. Reconnection requires log reconciliation before it can claim a current roster view.

Because the mesh is partitionable, replicas can temporarily have different roster views. There is no global membership consensus in the current offline architecture. Consequently, a sender can guarantee delivery only to the exact roster snapshot it records, not to an unknowable set of devices that may have joined elsewhere during a partition.

## 4. Immutable recipient snapshot

At message creation, the sender stores:
- message ID;
- group ID;
- canonical sorted member node IDs in the observed roster projection;
- roster-log checkpoint / digest;
- snapshot creation time and protocol version.

The snapshot is immutable for that message. A later JOIN does not retroactively become a recipient; a later LEAVE does not erase the obligation for an already-created message. The user may explicitly cancel or expire an obligation under a visible policy, but it must not be represented as successful delivery.

The snapshot and its digest must be bound into the signed/encrypted origin frame (protocol v7). Relays may update mutable transport routing metadata but may not alter the signed recipient snapshot.

Before sending, the UI must disclose when the roster is stale or incomplete. “Delivered to all group members” must not be displayed; the precise statement is “delivered to all members in this message's recorded snapshot.”

## 5. Per-member delivery receipts

A recipient emits a receipt only after:
1. authenticating the origin frame and its sender signature;
2. confirming its own node ID is in the immutable recipient snapshot;
3. committing the message to durable local storage;
4. committing a receipt record keyed by (group ID, message ID, recipient node ID).

The receipt contains message ID, recipient node ID, recipient public-key fingerprint, receipt type (accepted/delivered/read as distinct states), timestamp, and snapshot digest. It is signed by the recipient identity and group-encrypted for transport.

The sender verifies:
- signature and node ID/public-key binding;
- receipt message ID and snapshot digest;
- recipient is a member of that exact snapshot;
- receipt type is permitted by the UI's completion policy;
- duplicate receipts are idempotent.

A receipt from a member outside the snapshot is rejected. One member's receipt increments only that member's ledger row; it never deletes the whole message by itself.

The sender retires the durable message only when every snapshot recipient has reached the configured terminal state. If the sender itself is in the snapshot, local durable acceptance is recorded explicitly. Read receipts are optional and must not be conflated with delivery.

## 6. Outbox and offline behavior

Proposed durable tables:
- `membership_events(group_id, event_id, member_id, counter, event_type, payload, signature, created_at)`;
- `membership_state(group_id, member_id, public_key, state, last_counter, last_event_id)` (rebuildable projection);
- `message_snapshots(message_id, group_id, roster_digest, snapshot_json, created_at, policy)`;
- `member_receipts(message_id, member_id, receipt_type, receipt_id, payload, received_at)`.

Use SQLite transactions to persist a message, its immutable snapshot and all pending-recipient rows atomically. Use unique constraints on event IDs, (group_id, member_id, counter), (message_id, member_id, receipt_type), and receipt IDs. Never evict unacknowledged message snapshots or receipts due to a bounded cache policy; apply backpressure instead.

Offline members remain pending. A message may be marked “partially delivered” while one or more recipients are offline. Expiration, cancellation and retention are explicit user-visible terminal states, not ACKs.

## 7. Required adversarial tests

- Forged JOIN, LEAVE and receipt signatures are rejected.
- Public key / node ID mismatch is rejected.
- Replayed event IDs and counters are idempotent; conflicting counters are recorded as equivocation.
- A malicious member cannot mutate a previously signed snapshot.
- A receipt from a non-recipient is rejected.
- One receipt never retires a multi-member message.
- Duplicate receipts do not double-count.
- A member joining after message creation is not added to the old snapshot.
- A LEAVE received after message creation does not silently erase its pending obligation.
- Partitioned replicas merge the same event set to the same projection.
- A stale roster is visibly marked and never represented as globally complete.
- Outbox restart/crash tests prove message snapshot and receipt ledger are transactionally durable.
- A 3+ device physical test covers offline recipient reconnection and replay.

## 8. Implementation gates

Do not enable “all members delivered” or retire group broadcasts until all of these are true:
1. protocol v7 frame binding and compatibility policy are implemented;
2. JOIN/LEAVE event verification and local durable log primitives exist; event transport, replay synchronization and lifecycle integration are still required;
3. roster reconciliation and stale-view signaling are implemented;
4. immutable snapshots and per-member receipt ledger are transactionally persisted;
5. sender and receiver adversarial instrumentation tests pass;
6. physical multi-device offline/reconnect tests pass;
7. UI clearly distinguishes delivered, partially delivered, offline, expired and cancelled states.

Until all gates pass, retain the current fail-closed broadcast ACK behavior. The new local membership log is foundational storage only and must not be used to retire a broadcast.
