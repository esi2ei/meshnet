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
- **Implemented best-effort transport primitive:** `MeshCrypto.groupId` derives a stable domain-separated SHA-256 group identifier from the derived AES group key (the passphrase itself is never sent). `MeshForegroundService` appends a signed local JOIN on startup and a signed LEAVE on graceful stop; it republishes the current JOIN and requests synchronization when a transport peer connects. `MeshMembershipSyncProtocol` sends signed membership events in bounded chunks over existing device-signed, group-encrypted v6 ephemeral frames; peers request history when a transport peer connects. Chunk assembly is bounded (64 concurrent assemblies, 10-minute TTL, 24 fragments/event, 40 Base64 characters per fragment and 720-byte event records), uses a 96-bit group tag plus the full group ID inside the signed event, and rate-limits JOIN/LEAVE publication, sync requests and history responses. Future-dated membership events beyond five minutes of clock skew are rejected; sync pages use local append order rather than trusting peer timestamps.
- **Current pagination implementation:** `MeshGroupMembershipStore` exposes a rowid high-watermark and a bounded query ordered by local append rowid. `MeshMembershipSyncProtocol` fixes a snapshot upper bound and requests successive pages of up to 16 events. A page response advances the cursor only when every fragment of every event has been accepted by the send callback. The receiver rejects a changed snapshot, cursor regression and a jump to any cursor other than the exact next cursor the responder issued. Event signatures are verified before merge and duplicate event IDs remain idempotent. Instrumentation source covers 70 events, immediate continuation, out-of-order fragment arrival, snapshot exclusion of later rows and arbitrary cursor-jump rejection.
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

**Implemented local primitive (not yet connected to message sending):** `MeshGroupDeliverySnapshot` is signed by the sender and binds message ID, group ID, sender key, digest of the observed roster, sorted recipient node IDs/public keys and creation time. Snapshot creation accepts only signature-verified membership events, rejects incomplete/conflicted counter histories, validates every recipient key-to-node-ID binding and excludes the sender from the remote recipient list. `MeshGroupDeliveryLedger` atomically persists the immutable snapshot and pending-recipient rows. A later JOIN cannot alter the stored snapshot.

**Implemented local receipt ledger (not yet transported):** `MeshMemberDeliveryReceipt` signs message/group/member/key/snapshot digest/receipt ID/type/time with a domain-separated P-256 signature. The ledger rejects non-recipients, key substitution and wrong snapshot digests; stores receipt evidence append-only; treats exact retries idempotently; preserves receipt-ID equivocation as an error; and advances per-member state monotonically from ACCEPTED to DELIVERED to READ. Completion is true only when every stored snapshot recipient has DELIVERED or READ. Storage is bounded (6,000 snapshots, 250,000 recipient rows and 250,000 receipt events) and applies backpressure instead of evicting records.

These delivery primitives are not yet bound into MeshFrame v7, connected to the router/outbox or UI, or committed atomically with the original message. Membership events now page beyond the former 64-event cap over current v6 ephemeral frames, but continuation state is in-memory and best-effort: a process death or reconnect restarts reconciliation from the beginning. Pages are queued before cursor advance, yet they are not retained in the durable outbox. The delivery database is not encrypted at rest. The local observed roster is not globally authoritative.

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
- **Implemented local primitive:** the ledger performs these checks, binds each receipt to the exact stored snapshot, and validates the expected public key for that recipient;
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
1. protocol v7 frame binding and compatibility policy are implemented (current membership paging uses a bounded v6 ephemeral application envelope, not the final frame-bound protocol);
2. JOIN/LEAVE event verification, local durable log, automatic local JOIN, and snapshot-bounded paged v6 peer synchronization exist; crash-resumable cursor persistence, durable retry/outbox delivery, replay recovery and lifecycle/roster UX remain required;
3. roster reconciliation and stale-view signaling are implemented;
4. local immutable snapshots and per-member receipt events/recipient rows are transactionally persisted; integration with the original outbox message transaction remains required;
5. targeted API 36 instrumentation for local membership, snapshot and receipt primitives passes on the current code batch (run #179: 187/187 across 22 selected classes); live protocol-v7 message/router/outbox integration remains unimplemented;
6. physical multi-device offline/reconnect tests pass;
7. UI clearly distinguishes delivered, partially delivered, offline, expired and cancelled states.

Until all gates pass, retain the current fail-closed broadcast ACK behavior. The new local membership log is foundational storage only and must not be used to retire a broadcast.

## Current sync paging status — 2026-11-28

The former most-recent-64-only response is removed for updated peers. A responder now captures a rowid high-watermark, returns up to 16 signed events per page, and records the exact next cursor it has issued to that peer. The requester cannot jump over rows or change the snapshot. The continuation is still in memory only, and the chunks remain v6 ephemeral (not durable outbox messages). This is a bounded best-effort reconciliation improvement, not a crash-resumable or globally authoritative membership protocol.

The current PR adds targeted API 36 regression coverage for more than 64 events, immediate page continuation, reversed fragment arrival, rejection of an unissued cursor and legacy v6 compatibility. These tests passed in run #179 (`46103437101`) on code/test commit `798d12bebdcb8cb365c1cbd52df10...`; the final branch commit after this run contains documentation-only changes.

## Compatibility with older protocol-v6 peers

Updated peers request cursor paging with the extended five-field sync request. When a responder receives the original three-field `~GM1|S|<groupId>` request, it preserves the legacy exchange: one bounded page of at most 64 events and the original four-field total-count marker. If the sender has more than 64 records, it emits a partial-history warning; an older peer cannot consume the new cursor continuation. This maintains wire compatibility without falsely claiming full history delivery to older clients. To obtain all pages, both peers must support the extended cursor form.

Regression tests verify both the 70-event cursor path between updated peers and the legacy single-page marker for an older three-field request. Both passed in run #179 as part of the 187-case API 36 targeted suite.

## Targeted CI evidence — 2026-11-28

GitHub Actions run #179 (`46103437101`) passed on application/test commit `798d12bebdcb8cb365c1cbd52df10...`: Node 22 web typecheck/build, Android debug APK assembly and signature verification, and all 187 selected API 36 instrumentation tests (22 test classes; 0 failures, 0 errors, 0 skipped). The suite includes membership cursor paging, legacy v6 sync compatibility, immutable local delivery snapshot/receipt primitives and route relay/forged-hop regression. The full instrumentation suite and physical multi-device offline/reconnect tests remain unrun. Group delivery remains partial because these local primitives are not yet bound into protocol v7, the durable router/outbox, or the UI.
