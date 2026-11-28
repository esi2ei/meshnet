# MeshNet development roadmap

This document is the working source of truth for the completion branch. It distinguishes code present in main, CI-verified behavior, work in progress, and gates requiring physical devices or independent reviewers.

## Branch and delivery policy

- Stable baseline: `main` at merge commit `a5aecaab8af0f33ab161fba3c57c29cdda606fe7` (PR #30, 2026-11-28).
- Trial artifact only: release/trial-apk (PR #15). Do not put feature work on this branch.
- Mainline regression gate: PR #30 is merged. Run #193 validated its exact source head with Node 22 checks, Android debug APK assembly/signature verification, and 167/167 focused API 36 instrumentation tests; the full instrumentation suite remained intentionally skipped.
- Ongoing engineering: work/offline-mesh-completion. All follow-up source, test, and documentation work belongs here.
- Keep changes grouped by topic, with matching tests and documentation in the same commit where practical.
- Open or update one draft PR for this completion branch after a coherent batch is ready; do not merge it automatically.
- Never mark a physical-device, cryptographic-audit, smart-contract-audit, or store-release gate complete based only on emulator CI.

## Status vocabulary

- **Implemented**: source code is present.
- **CI-verified**: the corresponding build/test passed in GitHub Actions.
- **Device validation required**: must be exercised on physical Android hardware.
- **External gate**: requires an independent audit, owner-provided credentials, legal/store decision, or deployment evidence.
- **Not implemented**: do not advertise as available.

## Complete product and engineering roadmap

**This is the integrated execution plan** for the requirements discussed in this conversation and GitHub issues [#17–#28](https://github.com/esi2ei/meshnet/issues). The detailed capability-preservation rules remain in [FEATURE_PRESERVATION_PLAN.md](FEATURE_PRESERVATION_PLAN.md); issue-level evidence and implementation notes remain in the traceability matrix below.

### Product principles and non-negotiable decisions

1. **No feature reduction as a refactoring shortcut.** Existing dashboard, radio, messaging, voice, file/library, VPN, wallet, Devnet and source-viewer capabilities must remain present. Partial, prototype and planned items remain in scope and retain honest status labels.
2. **Offline-first core.** Discovery, local messaging, files, voice and the private intranet must work without a cloud account or an Internet connection wherever the relevant hardware is available. Internet access, wallet RPC, marketplace settlement and other online services remain explicit, opt-in extensions.
3. **Trust is separate from discovery.** Finding a device or receiving its advertisement never grants membership, identity ownership, private-file access or permission to relay a user's private data.
4. **Preserve data and protocol compatibility.** Existing identities, messages, outbox records, membership events, receipts, library content, pending transfers and wallet evidence must survive upgrades. Protocol v6 remains a compatibility baseline until a versioned successor is negotiated and tested.
5. **Do not overstate prototype behavior.** Shared-key group encryption is not per-device end-to-end session security; a local voucher is not money; a private IPv4 overlay is not public Internet sharing; an escrow source file is not a deployed or audited escrow service.
6. **Small, reversible delivery.** Move one responsibility at a time behind compatibility facades. No big-bang rewrite, silent bridge removal, schema reset, or automatic merge to `main`.

### Current audit baseline

The GitHub requirements set contains 12 open issues (#17–#28). Their state is intentionally not changed by this roadmap.

| Issue | Repository reality at audit | Plan |
|---|---|---|
| [#17 Network discovery/routing](https://github.com/esi2ei/meshnet/issues/17) | Partial: BLE/GATT, Wi-Fi Direct, Wi-Fi Aware, TTL relay, route observations and a local IPv4 overlay exist; physical convergence, route recovery, radio fallback and OEM background behavior are not proven | Phase 2 |
| [#18 Identity/trust/E2EE](https://github.com/esi2ei/meshnet/issues/18) | Partial: P-256 device identity, signed origins and encrypted local stores exist; shared group AES key, device approval/revocation, portability and per-device ratchets do not | Phase 3 |
| [#19 Spaces/messages/media](https://github.com/esi2ei/meshnet/issues/19) | Partial: direct messaging, queue, signed membership log, snapshots, receipt ledger and voice/file primitives exist; durable protocol-v7 group integration, context ACLs, full offline delivery, edit/delete/expiry and video are incomplete | Phase 4 |
| [#20 Public-channel governance](https://github.com/esi2ei/meshnet/issues/20) | Design only; no complete election, eligibility, complaint or manager-replacement lifecycle | Phase 9 |
| [#21 Reputation/trust endorsements](https://github.com/esi2ei/meshnet/issues/21) | Design only; device signatures alone do not provide Sybil-resistant account reputation | Phase 9, after identity |
| [#22 Distributed storage/search](https://github.com/esi2ei/meshnet/issues/22) | Partial: explicit publication, content addressing, bounded resumable transfer and local recovery exist; private ACL, authorized multi-hop search, encrypted peer backup and export/restore are missing | Phase 5 |
| [#23 Metering/marketplace/Internet sharing](https://github.com/esi2ei/meshnet/issues/23) | Not implemented: current VPN is a private 100.64.0.0/10 intranet, not a gateway/NAT or metered Internet service | Phase 7, after network/security |
| [#24 Contracts/receipts/accounting](https://github.com/esi2ei/meshnet/issues/24) | Partial: signed contract/receipt and encrypted journal primitives exist, but no receiver-measured live transfer integration, mutual settlement lifecycle or recovery export | Phase 8 |
| [#25 Wallet/real-money controls](https://github.com/esi2ei/meshnet/issues/25) | Prototype/partial: encrypted local vouchers and Devnet SOL transfer exist; no authoritative offline balance, global double-spend prevention or fiat settlement | Phase 8 |
| [#26 Peer arbitration](https://github.com/esi2ei/meshnet/issues/26) | Design only; depends on identity, reputation, signed contracts and evidence preservation | Phase 9 |
| [#27 Android background/local companion/UX](https://github.com/esi2ei/meshnet/issues/27) | Partial: bundled local dashboard, bridge, foreground service, permission controls and resource features exist; background/OEM, accessibility, onboarding and recovery need validation | Continuous; Phases 1, 2, 6 and 10 |
| [#28 Traceability/integration delivery](https://github.com/esi2ei/meshnet/issues/28) | Partial: detailed roadmap, focused API 36 CI and tests exist; exact-head bridge/status regression and complete requirement evidence are still being validated | Phase 0 and continuous |

The three major prerequisite chains are:

- **Network and communication:** requirements inventory → safe architecture boundaries → transport reliability → device trust/session security → durable message/group delivery → media and file completion.
- **Economy:** device trust → private routing and consent → receiver-measured transfer evidence → mutually signed contracts → auditable ledger/reconciliation → optional external settlement.
- **Community governance:** stable identity/admission → Sybil and abuse model → privacy-aware reputation → deterministic channel governance → peer arbitration and appeal.

### Phase 0 — Baseline, traceability and preservation

**Goal:** make the current product and all open requirements measurable before further restructuring.

- [x] Record the 12 requirement issues (#17–#28), current status and known acceptance gaps.
- [x] Record the complete 31-method Android JavaScript bridge contract against its TypeScript declarations; protect active UI calls with a Node CI guard.
- [x] Add the feature-preserving refactor contract and link it from the roadmap, architecture and README.
- [x] Extract dashboard runtime-status JSON serialization into a focused class without changing the existing bridge method or payload fields.
- [ ] Obtain green CI evidence for the exact latest branch head, including the new runtime-status serializer test and bridge guard.
- [ ] Create one traceability row for every visible user action: screen/control → bridge method → native owner → persistent store → test → physical/external evidence.
- [ ] Record the exact current baseline for test counts, Android API level, web toolchain, supported hardware and known limitations.
- [ ] Keep issue status conservative; no issue is closed solely because source code or a design exists.

**Exit gate:** each existing feature and each requirement has an owner, status, code/test evidence and a remaining acceptance statement. No undocumented deletion or untracked requirement is allowed.

### Phase 1 — Architecture, processing engines and data ownership

**Goal:** simplify internal responsibility without changing product behavior.

**Target logical architecture**

```text
React/Vite dashboard (all existing screens)
            |
      stable native bridge
            |
     application coordinator
            |
 +----------+-----------+------------+------------------+
 |          |           |            |                  |
Transport  Message    Identity/    Product capability  Query
supervisor engine     security     engines             engine
                                  files / voice / VPN /
                                  wallet / governance
 |          |           |            |                  |
 +----------+-----------+------------+------------------+
            |
       persistence and OS adapters
            |
 SQLite / Android Keystore / filesystem / radio / VPN
```

- [ ] Keep `MainActivity` as the Android UI/permission/bridge adapter, not the owner of all domain rules.
- [ ] Keep `MeshForegroundService` as the compatibility entry point while extracting transport supervision, router/outbox, membership, file/library, voice, VPN coordination and wallet orchestration behind narrow interfaces.
- [ ] Preserve every current `MeshNetNative` method and the workspace screens during extraction; add/keep contract tests before changing the bridge.
- [ ] Move one responsibility per reviewable change. Avoid changing radio lifecycle, cryptographic behavior, persistence and UI in the same refactor.
- [ ] Define typed request/result/error models for native bridge operations. Add bridge version/capability negotiation only as an additive change.
- [ ] Replace the duplicate/poll-heavy status path with native event delivery where available; keep a bounded polling fallback for reconnect/recovery and browser preview.
- [ ] Define ownership and thread boundaries: UI/main for rendering and platform UI callbacks, IO for database/files/network waits, CPU dispatcher for hashing/compression/serialization, and bounded serial actors/queues for mutable transport/session state.
- [ ] Give each long-running operation cancellation, timeouts, back-pressure, lifecycle ownership and observable error states.

**Data architecture decision from the conversation**

- **SQLite is the authoritative persistent database today.** Existing SQLite-backed stores are retained first; no second shadow database is introduced merely to make the architecture look modular.
- **Room is not a separate database engine alongside SQLite.** Room is an abstraction/DAO layer over SQLite. Evaluate it as a gradual access layer for suitable tables only if it can share the same database, migrations, constraints and transaction semantics. Do not copy records into a parallel Room database.
- **Query Engine:** a single application query/repository layer owns indexed lookup, filters, paging, ordering, transaction boundaries and projections for messages, peers, membership, receipts, library, wallet and activity. It reads/writes the authoritative stores; it is not another persistence engine.
- **JSON/Protocol Engine:** JSON is for validated bridge DTOs, settings/configuration, protocol envelopes and explicit export/import. It is not a second database. Binary/compact wire encoding may be evaluated for BLE limits, but only through versioned protocol negotiation and tests.
- [ ] Produce a schema ownership map and migration plan before any database consolidation.
- [ ] Decide table-by-table whether existing SQLite APIs remain or Room is adopted over the same file. Do not migrate all stores as one operation.
- [ ] Make message + immutable recipient snapshot + outbox rows atomic where the delivery design requires it.
- [ ] Add versioned migration fixtures, interrupted migration/restart tests, idempotency, backup/export and rollback/recovery evidence. Never wipe a user's store to fix a migration.

**Exit gate:** old bridge callers, stored data and protocol-v6 peers retain behavior; a documented database decision and tested migrations exist before storage ownership changes.

### Phase 2 — Network reliability, power and Android lifecycle

**Goal:** make the existing local transports work predictably across device combinations and background conditions.

- [ ] Build an explicit transport supervisor for BLE/GATT, Wi-Fi Direct and Wi-Fi Aware; each transport reports availability, permission state, discovery, connection, backoff and failure independently.
- [ ] Preserve BLE server/client dual roles and Wi-Fi Direct user-controlled actions. Keep Wi-Fi Aware optional and hardware-dependent.
- [ ] Add Android Wi-Fi Aware availability-change handling and restart/recovery instead of relying only on a one-time availability check.
- [ ] Replace permanent high-duty BLE scan/advertise behavior with measured operating profiles: discovery burst, connected/active transfer and idle/background. Never sacrifice active connection reliability without measured fallback.
- [ ] Evaluate supported background discovery/companion mechanisms and current Android foreground-service restrictions; document behavior by Android/API/OEM rather than promising always-on service.
- [ ] Define capability negotiation so devices without Aware, Wi-Fi Direct or BLE peripheral support can still use the remaining mutual transport.
- [ ] Verify link loss, stale peer expiry, relay loss, route convergence, duplicate suppression, TTL, bounded flooding, retry/back-pressure and no route-poisoning.
- [ ] Harden the IPv4 TUN packet plane: MTU/fragment boundaries, malformed packets, duplicate/conflicting fragments, incomplete assembly expiry, congestion, bounded retries and loop/resource limits.
- [ ] Verify DNS forwarding, transaction matching, SERVFAIL without uplink, captive portal/private DNS, upstream Network handover and decide/test TCP fallback for large DNS responses.
- [ ] Validate VPN consent/revoke, service death/recreation, foreground-service notification, Android 8–16 behavior, underlying network handover and OEM battery restrictions.
- [ ] Keep public Internet traffic outside the current overlay until the separate opt-in gateway project is designed and approved.

**Physical-device gate:** two-phone BLE/GATT bidirectional messaging; three-phone relay with end devices out of direct range; Wi-Fi Direct; supported-device Wi-Fi Aware; coexistence/fallback; screen-off/process-death/reconnect; VPN/DNS and portal through a relay. Record device model, OEM build, Android API, topology, logs and measured outcome.

### Phase 3 — Identity, trust and private-session security

**Goal:** move from signed device origin plus shared group key to an explicit trust and cryptographic lifecycle.

- [ ] Publish a threat model: malicious member/relay, spoofed identity, replay, Sybil accounts, compromised/lost device, traffic analysis, malicious file metadata, key loss and offline partitions.
- [ ] Separate device identity, user/profile identity, group membership and session keys. Support multiple devices per user without pretending one device key is a full account identity.
- [ ] Design QR/fingerprint-based onboarding, explicit device approval, blocking/reporting, leave, revoke, lost-device handling and recovery/portability with proof of ownership.
- [ ] Add per-recipient private message/file sessions so relays and unrelated group members cannot decrypt private payloads.
- [ ] Design secure group-key distribution, rotation and membership change semantics.
- [ ] Evaluate an established audited group/session protocol (e.g. MLS/OpenMLS or a maintained pairwise ratchet implementation) using a small Android JNI/NDK proof and persistence prototype before adoption. Do not integrate a candidate merely because it builds on a desktop host.
- [ ] Keep protocol v6 readers and senders during a documented migration window; define capability/version negotiation, unknown fields, downgrade rejection, replay policy, recovery and old-peer UX.
- [ ] Encrypt sensitive durable membership/delivery databases and user exports; keep Android Keystore keys non-exportable where appropriate.
- [ ] Add independent cryptographic review of the exact implementation and protocol transition.

**Exit gate:** threat-model closure, test vectors/interoperability, lifecycle/revocation/recovery tests, relay confidentiality tests and independent review. Until then, UI and docs must not label shared-key group traffic as Signal-grade E2EE.

### Phase 4 — Messaging spaces, durable group delivery and multimedia

**Goal:** complete the communication model without confusing real-time sessions with offline messages.

- [ ] Implement distinct authorization contexts for public space, public channels, private groups and one-to-one messages.
- [ ] Make signed JOIN/LEAVE synchronization durable and resumable across process death, partitions and reconnects. A page cursor must be persisted and bound to its exact event snapshot.
- [ ] Bind membership history, immutable recipient snapshots and per-member signed ACCEPTED/DELIVERED/READ receipts into a versioned protocol frame (planned v7), then integrate with router, durable outbox, UI and transactions.
- [ ] Atomically persist message identity, payload reference, immutable recipient snapshot and pending-recipient records before transmission.
- [ ] Keep fail-closed broadcast receipt retirement until every recipient in the original snapshot reaches an explicitly defined terminal state. A late joiner must not be added retroactively; a leave must not erase a prior obligation.
- [ ] Expose truthful states: local queued, accepted, relayed/forwarded where evidence exists, delivered, seen (only with consent), partially delivered, failed, expired and cancelled.
- [ ] Add privacy controls for read receipts, blocking, reporting, message retention and optional expiration.
- [ ] Implement edit history and deletion semantics with clear distinctions between local deletion, group tombstone and remote deletion request.
- [ ] Complete image/video/file attachments using bounded chunking, resumable transfer, per-chunk and whole-object integrity, quotas and access control.
- [ ] Preserve voice notes and live group voice; test permission timing, audio focus, interruption, invitation expiry, mute/end/reconnect and multi-speaker behavior.
- [ ] Keep queued store-and-forward messages separate from live audio/video streams. Do not queue a live-call stream as if it were a message.
- [ ] Add video calls only after real-time transport quality, congestion control, audio behavior and resource measurement are proven.

**Exit gate:** adversarial protocol tests, crash/restart tests, at least three-device tests with an offline recipient, and UI tests demonstrating truthful per-recipient delivery states.

### Phase 5 — Private library, distributed files and search

**Goal:** expand the existing offline library without exposing private data or losing content.

- [ ] Keep explicit owner publication, content-addressed SHA-256, integrity checks, resumable chunks, current quotas and non-destructive recovery.
- [ ] Introduce explicit visibility modes (private, group-authorized and public) with authorization enforced at both search and fetch.
- [ ] Encrypt private content and metadata; relays and backup hosts store opaque ciphertext and cannot infer file names or read contents.
- [ ] Implement reliable unpublish/revocation/expiry semantics and define what can remain cached by already-authorized peers.
- [ ] Build local-first hybrid search, followed by bounded multi-hop search with TTL, hop/node/time/result-size limits, deduplication, per-peer rate limits and incremental result streaming.
- [ ] Add owner-controlled storage allocation, quota, availability, retention, bandwidth and backup placement.
- [ ] Add encrypted export/restore and optional encrypted peer backups with owner-controlled key/revocation and expiry.
- [ ] Make portal access follow the same ACL rules as in-app search/fetch; no bypass through the HTTP portal.
- [ ] Add abuse tests for catalogue spam, oversized metadata, path traversal, decompression bombs, search floods and malicious chunk claims.

**Exit gate:** unauthorized peers cannot discover private metadata or fetch private bytes; corrupted or interrupted transfers never become verified library entries.

### Phase 6 — Performance, concurrency and game-development techniques

**Goal:** scale topology, media and data handling without changing correctness or silently dropping work.

These techniques are applied to the appropriate layer, not copied blindly from a game engine:

- **LOD (Level of Detail):** reduce topology-map detail for distant/low-priority visual nodes (for example hide labels or aggregate edge detail). LOD affects rendering only; it must not reduce peer discovery, routing, security checks, message delivery or stored data.
- **Culling:** render only visible nodes, links and labels in a large topology viewport. Off-screen network peers still remain active in the network model; occlusion or viewport culling must never cancel message, file or relay processing.
- **Asynchronous loading:** move database reads, file hashing, thumbnails, media decoding, imports/exports, source-browser indexing and large result sets off the UI thread. Use paging and incremental rendering.
- **Multithreading:** define bounded Kotlin coroutine dispatchers/executors for IO, CPU-heavy compression/hash/crypto and stateful per-peer/per-store actors. Avoid unbounded thread creation and concurrent mutation of protocol or SQLite state. Keep UI updates on the main thread.
- **Resource compression:** evaluate image thumbnails, audio/video profiles and transfer compression with CPU/battery/network measurements. Compress before encryption only where beneficial; do not recompress already-compressed media by default. Keep size limits, streaming decompression and decompression-bomb defenses. Preserve content hashes over a clearly defined canonical/original object so compression does not break SHA-256 identity or resumable chunks.
- **Radio and battery modes:** add user-visible balanced/low-power/active transfer modes only after physical measurement; show why a transport is sleeping or unavailable.
- **Query performance:** index common queries, bound result sets, page activity/messages/library listings and measure query latency and database growth. Use the Query Engine described in Phase 1 rather than adding ad-hoc JSON scans over unbounded histories.
- [ ] Establish a reproducible baseline for battery drain, CPU, memory, queue growth, radio scan duty, transfer goodput, packet loss, p50/p95 latency, UI frame time, database query time and file decode time.
- [ ] Add representative stress fixtures: many peers, long event history, large library catalogues, queued messages, fragmented files and repeated reconnects.
- [ ] Ship an optimization only with before/after measurements and unchanged correctness/regression results.

**Exit gate:** improved measured responsiveness/battery/throughput at a fixed feature and correctness baseline; no unbounded memory, queue or thread growth.

### Phase 7 — Opt-in Internet gateway and service marketplace

**Goal:** turn Internet sharing and provider services into a consent-based, safe and transparent extension, not an accidental property of the VPN.

- [ ] Complete legal, carrier, platform-policy and abuse-risk review for tethering/resale in target regions before enabling the feature.
- [ ] Design an isolated opt-in gateway service with explicit owner/consumer consent, per-device ACLs, firewalling, routing isolation, kill switch, abuse limits and no hidden traffic forwarding.
- [ ] Define provider offers: free, public fixed rate (same public rate for receivers) and negotiated offers; include capacity, validity, service scope, quality and data limits.
- [ ] Define billable bytes at the receiver/provider boundary and distinguish payload, protocol overhead, retransmissions, failed chunks and unrelated device traffic.
- [ ] Make route selection consider accepted price, user budget, security, trust, latency, throughput, route reliability and provider consent—not price alone.
- [ ] Require every relay to explicitly consent to a signed revenue share before the contract becomes active. Active shares cannot change without a new mutual agreement.
- [ ] Add spending caps, alerts, preview of price/terms, explicit accept/reject, pause and immediate stop controls.
- [ ] Keep Internet gateway and marketplace disabled by default until all isolation and legal gates pass.

**Exit gate:** receiver and provider independently reconcile the same measured transfer, no hidden traffic or charging, and legal/platform approval exists for the exact release region.

### Phase 8 — Contracts, accounting, wallet and settlement

**Goal:** evolve current cryptographic storage prototypes into verifiable accounting without claiming offline finality.

- [ ] Connect signed service contracts to actual offer acceptance and transfer initiation. Contracts must include parties, service, price, billable-byte rules, maximum volume/cost, validity, cancellation, relay shares and dispute policy.
- [ ] Persist contract copies and identifiers on both sides; require all affected parties to consent to material changes.
- [ ] Create receiver-measured receipts bound to the contract, transfer/chunk IDs, byte count and content digest; signatures must be mutual where required, monotonic and replay-resistant.
- [ ] Integrate receipts at the real accepted-payload boundary and reconcile them idempotently with transfer resume/retry so no chunk is double-billed.
- [ ] Link every ledger entry to the contract and receipt chain; support pause, fail, refund, reverse and dispute states.
- [ ] Keep the local-unit wallet visibly provisional. Define issuance authority, offline exposure limits, conflict/reconciliation rules and double-spend risk before calling it a balance.
- [ ] Add pending/confirmed/failed/reversed fiat states; external provider confirmation, not an offline peer receipt, is the finality boundary for fiat.
- [ ] Add budgets, alerts, spending limits, automatic stop and optional economic route selection.
- [ ] Add encrypted user-controlled export/restore, device migration and recovery without exposing seeds, credentials or plaintext wallet data.
- [ ] Keep Devnet separate and opt-in. Add a hard no-mainnet-by-default guard and exact cluster/mint/recipient/amount confirmation before any future production token flow.
- [ ] Build/deploy escrow only after program tests, deterministic program ID/admin ownership, canonical token mint, upgrade-authority policy and independent smart-contract audit. No production funds before approval.

**Exit gate:** end-to-end retry/crash/no-double-charge tests, bilateral offline verification, external provider confirmation for real-money claims, independent contract/program audit and recovery/export evidence.

### Phase 9 — Reputation, public governance and peer arbitration

**Goal:** implement decentralized community controls only after identity and abuse prerequisites exist.

- [ ] Resolve account/device identity, multi-device ownership, admission and Sybil cost before reputation scoring is used for eligibility.
- [ ] Define reputation inputs, decay, scope, thresholds, caps, endorsement rate limits and anti-reciprocal-farming controls. Keep unverified reports distinct from adjudicated violations.
- [ ] Build privacy-aware endorsements, evidence submission, right of response, appeal and human-verifiable audit history; no irreversible punishment from an unverified report alone.
- [ ] Implement public-channel eligibility, creation quota, activity/inactivity rules, voting window, active-member criteria, quorum, tie, replacement and recovery while peers are offline.
- [ ] Implement manager complaint review with reversible temporary suspension that does not remove ordinary member participation or right of response.
- [ ] Implement peer arbitration with verifiable random selection, independent eligible arbitrators, conflict-of-interest controls, timeout/replacement, minimum quorum, privacy-minimized evidence and appeal.
- [ ] Connect contract dispute state to arbitration outcomes only after evidence and ledger verification.
- [ ] Add Sybil, collusion, brigading, vote replay, duplicate-vote, quorum-loss and offline-partition simulations.

**Exit gate:** deterministic and reproducible decisions, verifiable signed records, privacy policy, appeal/reversal and adversarial tests across partitioned peers.

### Phase 10 — UX, release, hardware expansion and platform strategy

**Goal:** make the complete product comprehensible, recoverable and releasable.

- [ ] Keep current Network, Chat & media, Library, Wallet and Activity workspaces and all existing actions while improving hierarchy and onboarding.
- [ ] Audit every visible control for permission timing, disabled/busy states, progress, empty/error states, accessibility and stop/cancel/retry behavior.
- [ ] Show the actual transport and route evidence, battery mode, data used, file scope, message delivery state, price and contract terms where applicable.
- [ ] Separate local intranet, Internet gateway, online wallet, local voucher, confirmed payment and escrow labels so the UI cannot imply unavailable capabilities.
- [ ] Build identity, message, library and wallet recovery/export flows with clear data-loss warnings and owner control.
- [ ] Test first-use onboarding, QR trust, device loss, app upgrade and recovery with non-developer users.
- [ ] Complete physical-device matrix and accessibility/privacy review for the supported Android release scope.
- [ ] Keep trial/debug artifacts distinct from production artifacts. Create a protected release keystore outside Git; build/sign/verify AAB; complete privacy policy, data safety, support, permission rationale, store/legal and supply-chain review.
- [ ] Decide iOS only after Android transport, identity, persistence and bridge/domain APIs are stable. Keep all future capabilities in the cross-platform domain model; do not claim iOS support before a real client and device tests exist.
- [ ] Evaluate optional external radio adapters (for example LoRa companion hardware) as separate transport adapters after the phone-to-phone Android baseline is stable; do not make external hardware mandatory for the core app.

**Exit gate:** user-visible scope matches actual behavior, recoverability and data-safety evidence exists, the signed release artifact is reproducible and store/legal/security approvals are complete.

### Cross-phase engineering rules

- Every implementation batch includes: linked issue(s), capability impact, code owner, test plan, migration/protocol compatibility note, rollback note and documentation update.
- Prefer reusing successful tests for unchanged code. New or modified behavior must be tested on the exact new head; do not repeatedly rerun unrelated full suites solely for activity. The manual full Android suite, physical-device matrix and external audits remain separate, explicitly scheduled gates.
- CI green means code/build/emulator evidence only. It does not prove physical radios, battery, OEM background behavior, security audit, legal compliance or settlement.
- No issue is closed until its own acceptance criteria are satisfied with evidence. A prototype or document alone does not close an issue.
- All work remains on `work/offline-mesh-completion` and reviewable Draft PRs until explicitly approved. Never merge into `main` automatically.

### Next execution order

1. Complete Phase 0 validation for the exact current PR head (bridge preservation guard, web checks, Android build and focused runtime-status instrumentation test).
2. Continue Phase 1 with a baseline bridge/service responsibility map and the SQLite/Room/JSON decision record; do not make a schema migration yet.
3. Extract one low-risk responsibility from the large foreground service behind a compatibility facade, with characterization tests.
4. Start Phase 2 device evidence and transport recovery work, keeping the existing transports and user controls.
5. In parallel at the design/test level, complete the Phase 3 threat model; do not make marketplace or wallet settlement depend on unfinished security.
6. Proceed through Phases 4–10 only when their explicit prerequisite gates pass. Their scope remains preserved now, even when execution is later.

## Feature-preserving refactor contract

The mandatory rules and capability inventory are maintained in [docs/FEATURE_PRESERVATION_PLAN.md](FEATURE_PRESERVATION_PLAN.md). This work is a structural refactor, not a reduction of product scope.

- [x] Preserve the current product inventory and distinguish implemented, partial, prototype and planned capabilities.
- [x] Inventory all 31 native bridge methods, reconcile their Kotlin and TypeScript declarations, and document their UI areas, native owners, stores and existing test evidence in the feature-preservation contract.
- [ ] Add focused automated runtime bridge/status tests and close the per-feature behavior/device gaps listed in the contract.
- [ ] Add characterization/contract tests before moving each responsibility.
- [ ] Keep existing bridge and service entry points as compatibility facades while internals are extracted incrementally.
- [ ] Preserve protocol v6 behavior while any future protocol version is negotiated and tested.
- [ ] Require versioned, recoverable, no-data-loss migrations before changing database ownership or schema.
- [ ] Publish a capability-diff report for each structural batch; unexplained feature or data loss blocks merge.
- [ ] Keep physical-device, OEM background, battery, cryptographic-audit, smart-contract-audit and release gates open until evidence exists.

**Execution order:** inventory and behavior capture → stable interfaces/facades → transport lifecycle separation → durable messaging/security integration → product-store and wallet boundaries → device/regression proof. This order sets dependencies; it does not remove or cancel any feature in issues #17–#28 or other documented product goals.

### First behavior-preserving refactor slice

- [x] Move runtime-status JSON construction into `MeshRuntimeStatusSerializer` while keeping the existing `getRuntimeStatus` bridge command and payload field contract.
- [x] Add tests for complete status fields and explicit null/empty behavior; include them in the focused API 36 suite.
- [ ] Validate the bridge guard, Android build and focused instrumentation results on the exact current head before accepting the slice.

## Baseline already in main

| Area | Source status | Verified boundary |
|---|---|---|
| Android app and embedded offline dashboard | Implemented | CI builds debug APK and web bundle |
| BLE/GATT, Wi-Fi Direct, Wi-Fi Aware transports | Implemented | Emulator tests do not validate real radios or OEM behavior |
| Protocol v6 signed device-origin identity | Implemented | P-256 Android Keystore identity and signed plaintext digest; group encryption still uses a shared AES-GCM key |
| Directed messages and destination-bound receipts | Implemented | Does not establish every-member group delivery or offline-member delivery |
| Offline content library | Implemented | Explicit publication, content addressing and SHA-256 verification; current sharing is group-visible |
| Private IPv4 overlay / VPN / DNS / HTTP portal | Implemented | Android user consent required; local 100.64.0.0/10 only; not public Internet access |
| Solana wallet | Prototype | Devnet wallet authorization and native SOL transfer; no production USDC or escrow settlement |
| Escrow program | Source prototype | Not built, tested, deployed or audited; no real funds |
| iOS client | Not implemented | Deliberately deferred |

## Active completion work

### NET-01 — Address lifecycle and neighbor convergence

- [x] Expire remote overlay address records after a bounded TTL.
- [x] Refresh node announcements periodically while the VPN is active.
- [x] Add deterministic-clock test for expiry and relearning.
- [ ] Verify three-node convergence, duplicate addresses and peer churn on real phones.
- [ ] Add evidence for route recovery after relay loss/reconnect.

### NET-02 — DNS upstream correctness

- [x] Bind upstream UDP DNS socket to the selected non-VPN Android Network.
- [x] Connect the socket to the selected DNS server and validate transaction ID / response flag.
- [x] Add tests for malformed, mismatched and non-response packets.
- [ ] Validate private DNS, captive portals, multi-network devices and OEM network transitions.
- [ ] Decide whether TCP DNS fallback is in scope; it is currently unsupported.

### NET-03 — VPN lifecycle and failure handling

- [ ] Verify user consent, start/stop, revoke, service death and process recreation on Android 8–16.
- [ ] Verify behavior when the underlying network changes while the TUN remains active.
- [ ] Ensure notification and foreground-service eligibility on supported Android versions.
- [ ] Test simultaneous radio loss and VPN service disconnection.
- [ ] Measure battery usage and background restrictions across OEM builds.

### NET-04 — Packet plane reliability and limits

- [ ] Test packet sizes 1 byte, MTU-1, MTU, oversized, malformed headers, invalid checksums and truncated UDP frames.
- [ ] Add bounded retry/back-pressure or clearly document best-effort loss semantics.
- [ ] Add destination-aware route expiry/measurement and loop prevention tests.
- [ ] Test fragment duplicates, conflicting duplicates, incomplete assembly expiry and sender abuse.
- [ ] Record measured throughput, latency, memory and CPU with three physical devices.

### SEC-01 — Device trust and session security

- [x] Stable per-install P-256 signing identity stored in Android Keystore.
- [x] Signed origin digest and public key carried in protocol v6; receiver verifies origin after decryption.
- [x] Domain-separated detached P-256 signatures available for application records; local group JOIN/LEAVE event records are signature-verified and persisted in a bounded append-only SQLite log.
- [x] Local immutable group recipient snapshots signed by the sender, derived only from verified membership events, with fixed recipient keys and a roster digest.
- [x] Local append-only per-member receipt evidence with signed ACCEPTED/DELIVERED/READ states, snapshot binding, recipient/key validation, idempotent retries, monotonic state and bounded transactional SQLite persistence.
- [x] Derive a stable domain-separated group ID from the derived group encryption key (without exposing the passphrase); append signed local JOIN on service startup and signed LEAVE on graceful stop; republish JOIN and exchange signed membership events in bounded chunks over v6 ephemeral frames when peers connect.
- [ ] Replace the best-effort latest-64 event sync with paged, crash-resumable reconciliation; bind membership events, snapshots and receipts into protocol-v7 frames; integrate delivery snapshots/receipts with router/outbox/UI and atomically persist the original message with its snapshot. The local databases are not encrypted at rest and do not provide a globally authoritative roster.
- [ ] Design and implement explicit onboarding, fingerprint verification, device approval and revocation.
- [ ] Integrate an audited session protocol (vodozemac is a candidate only; it is not integrated).
- [ ] Add per-device ratchets, forward secrecy, secure group key distribution and rotation.
- [ ] Add downgrade/replay/recovery/interoperability tests and an independent cryptographic review.

### DATA-01 — Offline library and content sharing

- [x] Explicit publish; content-addressed SHA-256 IDs and metadata.
- [x] Bounded resumable transfer and integrity verification before promotion.
- [ ] Add clear group visibility / access warnings and reliable revoke/unpublish semantics.
- [ ] Evaluate private unicast transfer instead of current group-visible transport.
- [ ] Test crash recovery, interruption, quota pressure, malicious catalogue records and relay changes on hardware.

### UX-01 — Product usability and disclosure

- [x] Workspace organized into Network, Chat & media, Library, Wallet and Activity sections.
- [ ] Audit every visible control for a working action, permission handling, loading/error/empty states and accessibility.
- [ ] Ensure "offline intranet", "Internet access", "wallet balance", "voucher", "Devnet" and "escrow" labels cannot imply capabilities that are not implemented.
- [ ] Test onboarding and recovery with a first-time user on a physical phone.
- [ ] Update screenshots, in-app help and privacy/permission disclosures for the actual release scope.

### PAY-01 — Wallet and escrow

- [x] Local offline signed voucher/receipt prototype, explicitly not real money.
- [x] Native SOL transfer prototype through an MWA-compatible wallet on Devnet.
- [ ] Verify wallet authorization restore/deauthorization, transaction serialization and RPC confirmation on Devnet.
- [ ] Add no-mainnet-by-default guard and explicit cluster/mint/network confirmation for any future token transfer.
- [ ] Build/test escrow program, verify exact program ID and admin key ownership, pin canonical token mint and audit the exact deployed binary.
- [ ] Complete external smart-contract audit, dispute/upgrade-authority policy and legal review.
- **Release restriction:** no real funds, mainnet USDC, production escrow or "settled payment" claims until all external gates pass.

### REL-01 — Android release engineering

- [x] Debug build succeeds in CI and APK signature is verified.
- [x] API 36 emulator instrumentation suite passes for the trial build.
- [ ] Create and back up a production release keystore outside the repository.
- [ ] Build a signed release AAB, verify certificate and versioning, and keep all credentials out of Git.
- [ ] Complete store listing, privacy policy, support contact, data safety and permission rationale.
- [ ] Run release-candidate checks on the supported physical Android device matrix.
- **Release restriction:** the delivered APK is debug-signed; it is not a Play Store / production build.

### IOS-01 — iOS

- [ ] Deferred. Do not start a port until Android network/security architecture and release scope are approved.

## Required physical-device evidence

Record evidence in a release report with date, device model, OEM build, Android API level, radio capability, test topology, steps, result, logs and defects.

- [ ] Two-phone BLE/GATT bidirectional chat and reconnect.
- [ ] Three-phone BLE relay with end nodes out of direct range.
- [ ] Wi-Fi Direct discovery, connect, transfer and recovery.
- [ ] Wi-Fi Aware discovery and transfer on devices that support it.
- [ ] BLE + Wi-Fi coexistence and transport fallback.
- [ ] Offline IPv4 traffic between nodes over one relay.
- [ ] .mesh DNS resolution and local HTTP portal download.
- [ ] External UDP DNS with uplink, no-uplink SERVFAIL, and network handover.
- [ ] VPN permission revocation, process death, service restart and OEM battery restrictions.
- [ ] Library upload/download interruption, resume, digest verification and quota.
- [ ] File and voice interactions with permissions, backgrounding and audio focus.
- [ ] Battery, memory, CPU, packet loss, latency and throughput measurements.

## Exit criteria

A completion PR can be considered code-complete only when:

1. All Android and web CI jobs for its head commit are green.
2. New packet, VPN, DNS and library edge cases have automated regression coverage.
3. Documentation accurately describes implemented features and known limitations.
4. No secrets, signing material, test wallet seed phrases or credentials are committed.
5. Every remaining physical-device or external-audit gate is explicitly listed and has an owner/evidence requirement.

Production readiness is a separate decision and requires the physical-device evidence, security review, escrow audit (if escrow remains in scope), owner-managed signing material, store/legal disclosures and final release approval.


## GitHub issue traceability (issues #17–#28)

This matrix is intentionally conservative. “Partial” means that some code exists but the issue's acceptance criteria are not satisfied. An issue must not be closed merely because a related prototype or document exists. The paths below are evidence entry points, not proof that the full requirement is complete.

| Issue | Area | Current evidence | Status | Remaining acceptance work |
|---|---|---|---|---|
| [#17](https://github.com/esi2ei/meshnet/issues/17) | Offline discovery and multi-hop routing | `MeshBleTransport.kt`, `MeshWifiDirectTransport.kt`, `MeshWifiAwareTransport.kt`, `MeshRouter.kt`, `MeshRouteRegistry.kt`, `MeshIpPacketRouter.kt`; forwarding no longer uses attacker-controlled `hops` membership to suppress a relay, and a node forwards a frame at most once locally with bounded TTL | Partial | Authenticated per-hop/path attestations, route poisoning/loop/resource-abuse tests, automatic cross-transport convergence, physical multi-phone relay/recovery and Android background/OEM evidence |
| [#18](https://github.com/esi2ei/meshnet/issues/18) | Device identity and end-to-end security | `MeshDeviceIdentity.kt`, `MeshCrypto.kt`, `MeshSecretStore.kt`, `MainActivity.kt`, `MeshWebViewSecurityPolicy`; external WebView subresources and navigations are rejected and CSP disallows frames | Partial; the WebView/native bridge boundary is hardened for bundled assets, but shared group-key encryption remains | Per-device private-session encryption, audited ratchet/forward secrecy, secure group key distribution/rotation, onboarding/fingerprint verification, device export/recovery/revocation, downgrade/replay tests and independent audit |
| [#19](https://github.com/esi2ei/meshnet/issues/19) | Messaging, groups and multimedia | `MeshRouter.kt`, `MeshQueueStore.kt`, `MeshGroupMembershipStore.kt`, `MeshMembershipSyncProtocol.kt`, `MeshGroupDeliveryLedger.kt`, `MeshFileTransfer.kt`, `MeshLiveVoiceCall.kt`, `PhoneWorkspace.tsx`; signed JOIN/LEAVE log and snapshot-bounded rowid cursor pages over v6 ephemeral frames; pending voucher/ack chunks now use the wallet's encrypted store | Partial; membership sync pages beyond 64 within a fixed rowid snapshot, but continuation is in-memory/best-effort; snapshots/receipts remain local primitives with no router/outbox/UI integration; broadcast ACK remains fail-closed | Persist sync checkpoints across process death; bind events/snapshots/receipts into protocol v7; integrate receipt transport and verification with router; atomically bind message creation to snapshot/outbox; retire only when every snapshot recipient reaches terminal state; adversarial + 3-device physical tests; enforce public/group/direct ACLs, private one-to-one/group E2EE, edit history/deletion/expiry, video calls and offline store-and-forward privacy/retention |
| [#20](https://github.com/esi2ei/meshnet/issues/20) | Public-channel governance | Design gates in `docs/DISTRIBUTED_GOVERNANCE_REPUTATION_MARKETPLACE_ARBITRATION.md`; no complete governance lifecycle in code | Not implemented; design documented | Resolve account identity/admission and Sybil prerequisites; implement signed proposals/votes, quorum/tie/inactivity rules, reversible manager suspension/replacement, appeal, audit trail and offline governance tests |
| [#21](https://github.com/esi2ei/meshnet/issues/21) | Reputation and trust | Design gates in `docs/DISTRIBUTED_GOVERNANCE_REPUTATION_MARKETPLACE_ARBITRATION.md`; device-origin signatures are not a reputation system | Not implemented; design documented | Resolve account identity/admission and Sybil prerequisites; implement scoped signed endorsements, verified/unverified evidence separation, bounded scoring/decay, response/appeal, privacy controls and adversarial tests |
| [#22](https://github.com/esi2ei/meshnet/issues/22) | Distributed file storage and search | `MeshOfflineLibrary.kt`, `MeshFileTransfer.kt`, `MeshOfflineLibraryTest.kt`; damaged local catalogue rows preserve content blobs; over-quota recovery is read-only and allows explicit owner removals; unindexed blobs survive interrupted index commits and can be re-indexed | Partial; current visibility remains group-wide and private ACL/search is absent | Private encrypted content and metadata, authorized discovery, bounded multi-hop search, explicit encrypted export/restore, tested owner quotas/revocation/expiry, encrypted peer backups, crash/recovery and abuse tests |
| [#23](https://github.com/esi2ei/meshnet/issues/23) | Data metering, offers and Internet sharing | Current VPN is a private `100.64.0.0/10` intranet; no gateway/NAT or metered marketplace; design gates in `docs/DISTRIBUTED_GOVERNANCE_REPUTATION_MARKETPLACE_ARBITRATION.md` | Not implemented; design documented | Legal/carrier review, isolated opt-in gateway, per-peer consent, byte-accurate receiver metering, free/fixed/negotiated signed offers, relay consent, cheapest acceptable route, spending caps and online fiat settlement |
| [#24](https://github.com/esi2ei/meshnet/issues/24) | Signed contracts and fair settlement | `MeshServiceContract.kt`, `MeshTransferReceipt`, `MeshTransferReceiptStore.kt`: dual P-256 signatures, full-chain verification, AES-GCM journal and fail-closed recovery; `MeshOfflineWalletStore.kt` encrypts vouchers/history/replay IDs and pending transfer/ack chunks with wallet-bound GCM AAD | Partial; journal and wallet stores remain primitives, not connected to live offers/receiver-accepted-byte measurement/receipt exchange/settlement; export/restore absent | Integrate at receiver accepted-payload boundary, calculate exact digest/bytes, mutual receipt exchange, atomic wallet reconciliation, crash/retry/no-double-billing tests, encrypted export/restore and independent review |
| [#25](https://github.com/esi2ei/meshnet/issues/25) | Offline wallet and real-money controls | `MeshOfflineWalletStore.kt` encrypts replay IDs, received history, outgoing vouchers and pending voucher/ack chunks with wallet-bound AES-GCM / Android Keystore; legacy preferences migrate; malformed rows fail closed and receipt capacity back-pressures instead of evicting | Partial; encrypted local records remain provisional vouchers, not a balance ledger or global offline double-spend control; Devnet SOL remains a prototype | Offline credit exposure limits/reconciliation, pending/confirmed/failed/reversed fiat ledger, provider integration, contract-linked receipts, budgets/caps/alerts/auto-stop, encrypted export/restore and recovery. Never label vouchers or Devnet SOL as settled fiat |
| [#26](https://github.com/esi2ei/meshnet/issues/26) | Peer arbitration and disputes | Design gates in `docs/DISTRIBUTED_GOVERNANCE_REPUTATION_MARKETPLACE_ARBITRATION.md`; existing Anchor escrow remains an un-deployed prototype and is not connected to Android receipts | Not implemented; design documented | Account eligibility, verifiable randomness, independent quorum/timeout/replacement, privacy-minimized evidence, conflicts/collusion, appeal, settlement integration and independent contract audit |
| [#27](https://github.com/esi2ei/meshnet/issues/27) | Android operation, local web companion and UX | Android runtime and bundled WebView dashboard; strict bundled-origin resource policy/CSP; UI runtime status exposes library health, quota and corrupt-blob count | Partial | Complete authenticated/asynchronous bridge and accessibility review; add explicit battery/data/storage/spending controls, accurate queue/relay/delivery/billing states, identity/data export-recovery and physical-device background/OEM validation |
| [#28](https://github.com/esi2ei/meshnet/issues/28) | Traceability, integration tests and staged delivery | This matrix, `RELEASE_TEST_PLAN.md`, `ARCHITECTURE.md`, encrypted receipt/wallet/pending-envelope storage, WebView origin policy, snapshot membership paging, route-forwarding hardening and catalogue recovery; design gates for governance/reputation/marketplace/arbitration | Partial | Complete the current-head API 36 focused test run; attach passing evidence per criterion; finish live transfer/offer/wallet integration and adversarial tests; keep physical-device, legal and independent-audit gates separate |

### Verification boundary for this work batch

- Receipt-journal encryption and its regression-test source were committed to `work/offline-mesh-completion` and PR #29 with `[skip ci]`. No local build, instrumentation test, or CI workflow was run for this batch.
- GitHub Actions run #147 (run ID `46093149165`) passed on commit `53c4c8a5f8bb369df20f213a477aede83e15cfc8`: Node 22 typecheck and production web build, Android `:app:assembleDebug`, and APK signature verification succeeded. API 36 instrumentation was skipped because it is a manual opt-in. Run #147 predates the receipt-journal encryption commits and does not validate the current branch head.
- Earlier run #138 failed Android assembly; its recorded `itemJson` and receipt-hash compiler errors were subsequently corrected in source. Do not mix that failure with the later success on run #147 or treat either result as validation of later commits.
- The AES-GCM journal code and its new instrumentation-test source were added after run #147; no later workflow run covers the branch state containing these changes.
- The receipt journal now encrypts data using AES-GCM and a non-exportable Android Keystore key, migrates valid legacy JSON through an atomic write, and fails closed if the key is missing or authentication fails. Android backup was already disabled by `android:allowBackup="false"`; a deliberate encrypted export/restore and device-migration flow remains open.
- Issue #24 remains open: the journal is not yet integrated with accepted-payload measurement, transfer receipt exchange, wallet reconciliation or settlement. Membership/outbox databases have separate at-rest protection gaps. No issue is closed and no production-readiness claim is made.
- The current VPN is a private overlay/intranet only. Internet gateway/NAT, public Internet resale, metered sharing and automatic cheapest-provider selection are not implemented.

## Incremental audit fixes — 2026-11-28

- **Route poisoning mitigation (partial):** route learning now records only the cryptographically verified frame origin through the actual ingress peer. The mutable hops list is no longer trusted as a route proof. This prevents a fabricated hop list from teaching a route to an unrelated victim. Per-hop signed path attestations, authenticated peer sessions, and stronger loop/TTL guarantees remain open. Forwarding is also limited to one relay per node per frame; mutable hop counts no longer influence duplicate-forwarding decisions.
- **Outbox data preservation:** the outbox no longer deletes the oldest rows to satisfy its 6,000-entry cap. A full queue rejects the new enqueue with false (backpressure) and preserves existing unacknowledged frames. Automatic outbox age-based deletion was removed; frame/replay retention is now 30 days. User-visible expired-message management and per-member group delivery receipts remain open.
- **IPv4 correctness:** incoming overlay packets now require a valid IPv4 header checksum. Regression-test packet builders generate checksums and a corrupted-header test is included.
- **Address collision recovery (partial):** remote address-conflict records expire after the same 180-second claim TTL, allowing fresh announcements to be learned. A collision with this device's own stable address remains fail-closed; dynamic address leases/reassignment and deterministic collision-free allocation remain open.
- **CI cost controls:** the duplicate Node 24 matrix job was removed (Node 22 remains), npm install reuses the setup-node cache keyed by package.json, and the tested web dist artifact is shared with Android packaging so dependencies/build are not repeated. Android emulator instrumentation is opt-in via manual workflow dispatch. Pull-request CI still performs the web checks and Android debug assembly; emulator instrumentation is reserved for explicit validation.

**Verification boundary:** the earlier network hardening passed the applicable web/Android build and APK-signature checks in run #147 on commit `53c4c8a`; emulator instrumentation was skipped. Later receipt-policy and encrypted-journal changes are newer than that run and remain unverified. Physical-device checks and external security audits remain separate gates.


## Post-merge continuation — 2026-11-28

PR #16 was merged into `main` at `d3ecf7e5d734f577dc6920d4250b4597baeaa903`. The continuation branch `work/offline-mesh-completion` has been recreated from that merge commit so follow-up work remains isolated from `main`.

### Additional receipt-policy hardening

- **Implemented:** `MeshRouter` now rejects ACK/receipt frames by default when no explicit acknowledgement validator is supplied. The previous default accepted every signer, which was inconsistent with the fail-closed delivery policy.
- **Regression coverage updated:** the broadcast receipt test now exercises the default-deny path; the directed-delivery test supplies an explicit destination identity validator.
- **Not yet verified:** no local build, instrumentation test, or CI run has been executed for this follow-up. The change is source-level only until those checks are authorized and completed.

### Active issue state after PR #16

The issue traceability matrix above remains authoritative for issues #17–#28. All remain open until their acceptance criteria have implementation and evidence. In particular:

- **#19 messaging/groups:** local signed membership/snapshot/receipt primitives are not yet integrated with v7 frames, router, outbox, UI, and atomic message persistence. Group broadcast ACK remains fail-closed.
- **#17 networking, #18 security, #22 file sharing, #27 Android/UX:** partial implementations still require adversarial, recovery, privacy and physical-device validation.
- **#20 governance, #21 reputation, #23 Internet sharing, #26 arbitration:** remain unimplemented or prototype-only; do not advertise as available.
- **#24 contracts and #25 wallet:** local primitives/prototypes are not production metering or real-money settlement.
- **#28 traceability:** remains the cross-cutting gate for automated integration evidence and staged delivery.

No issue is closed by this continuation. No production-readiness claim is made. Preserve `[skip ci]` until the user explicitly authorizes validation runs.

## Encrypted receipt-journal hardening — 2026-11-28

- **Implemented in source:** `MeshTransferReceiptStore.kt` encrypts the bounded receipt journal with AES-GCM. The encryption key is generated and retained by Android Keystore and is not stored in the repository or alongside the journal.
- **Migration and failure policy:** valid legacy plaintext JSON is parsed and migrated through `AtomicFile`; a missing encryption key, malformed encrypted envelope, or failed authentication raises an error instead of silently creating an empty journal.
- **Regression source added:** tests cover encrypted-at-rest contents, reopening and chain verification, legacy JSON migration, missing-key failure, and ciphertext tampering. These tests have **not** been executed on the current head.
- **Backup/recovery boundary:** Android backup is disabled in `mobile/app/src/main/AndroidManifest.xml`. This prevents automatic backup but does not replace an explicit encrypted export/restore flow; device migration and recovery remain open.
- **Issue #24 remains Partial:** accepted-payload measurement/digest, two-party receipt exchange in the live transfer path, wallet-ledger reconciliation, end-to-end no-double-billing evidence and independent cryptographic review are not implemented by this storage hardening.
- **Validation:** run #147 predates these commits. The branch state after these source and documentation updates has not been built or tested. Preserve `[skip ci]` until the owner authorizes validation.

## Cross-cutting hardening and targeted validation — 2026-11-28

- **Wallet at-rest security (source + test coverage):** `MeshOfflineWalletStore.kt` uses AES-GCM with a non-exportable Android Keystore key for replay IDs, incoming history, outgoing vouchers and in-progress voucher/ack chunks. Key-specific authenticated data binds values to the wallet and preference field. Legacy string-set/JSON and the old separate plaintext pending-envelope preference migrate only after encrypted persistence succeeds. Missing keys, unknown envelopes, authentication failures and malformed rows fail closed. Receipt-history capacity back-pressures rather than evicting older receipts. These remain provisional offline vouchers, not a balance/settlement system.
- **WebView boundary (source + test coverage):** `MeshWebViewSecurityPolicy` only accepts HTTPS resources from the exact appassets host, without user-info or non-default ports, under `/assets/web/`. All other subresource requests receive a local 403 response rather than falling through to network access. The bundled dashboard CSP also denies frames and network connections. This reduces exposure of `addJavascriptInterface` to untrusted frames; it is not a substitute for an audited per-origin bridge or a private-session protocol.
- **Membership synchronization (source + test coverage):** `MeshGroupMembershipStore` exposes rowid high-watermarks and 16-event pages. `MeshMembershipSyncProtocol` fixes a snapshot, accepts only the exact issued continuation cursor, queues every event fragment before advancing, verifies signed event chunks and merges event IDs idempotently. This removes the former 64-event response cap. Continuation state is still in memory/best-effort over v6 ephemeral frames; process-death resume, durable outbox retention, protocol-v7 binding, router/outbox atomicity and authoritative membership remain open.
- **Targeted tests and CI evidence:** the focused PR suite comprises 22 Android instrumentation classes and 187 `@Test` cases. It covers encrypted receipt/wallet/pending-envelope storage, legacy migration and fail-closed corruption/key-loss behavior; WebView origin policy; 70-event membership snapshot paging, immediate continuation, cursor-jump rejection and legacy v6 compatibility; route relay/receipt behavior; immutable group delivery ledger; offline-library corruption/over-quota/orphan recovery; queue, route registry, IP packet routing, file transfer, portal, peer registry, device identity, crypto, frame, contract, wallet authorization and socket policy. **Run #179 reported 187/187 tests passing** for application/test commit `798d12bebdcb8cb365c1cbd52d60552d5e431570`; however, a post-run audit found the sender and receiver in `MeshMembershipSyncProtocolTest` shared one SQLite database. Therefore, its membership-sync result is not accepted as independent-store/network-sync evidence until the corrected fixture is revalidated. Other test results remain historical evidence for that exact SHA only.
- **Remaining product-level scope:** issues #17–#28 are not all complete. Governance (#20), reputation (#21), real Internet sharing/marketplace (#23), peer arbitration (#26), protocol-v7 group receipt integration (#19), receiver-measured billing (#24), real-money settlement (#25), and physical-device/OEM evidence (#17/#27) still need substantive work and external/device validation. Do not advertise missing functionality as available.
- **Validation policy:** PR CI runs the focused Android API 36 instrumentation classes for wallet encryption, receipt journaling, membership pagination, WebView policy and router receipt handling, in addition to web checks and Android APK assembly. The full instrumentation suite remains a manual workflow option.

## Android CI compile triage — 2026-11-28

- Run #154 (commit `4718f976bda4c839f90e5b2ff205efe12bc541eb`) passed the web typecheck and production bundle but failed during Android Kotlin compilation before instrumentation. Compiler output identified `MeshGroupDeliveryLedger.kt` using `String` constants with `String.matches(Regex)` and calling a `sha256` helper that was scoped only inside the ledger class; `MeshGroupMembershipStore.kt` referenced a private companion constant from the top-level event model.
- **Source correction committed:** snapshot/receipt validation patterns are now `Regex` values; a file-scope SHA-256 helper is available to the snapshot and receipt data classes; the membership timestamp skew bound is file-scoped and accessible to event verification.
- **Wallet integrity follow-up:** malformed JSON rows are no longer skipped by `history()`, `outgoingHistory()` or `pendingOutgoingVouchers()`. Full receipt history now rejects a new voucher with a clear capacity error rather than deleting the oldest receipt. Regression-test source covers malformed legacy records.
- The workflow captures Android build and instrumentation logs as a diagnostic artifact on failure. Run #179 is the final successful focused API 36 validation for the current application/test code; run #154 remains a historical Kotlin-compile failure and did not execute instrumentation.

## Additional hardening and design gates — 2026-11-28

- **Routing loop/DoS hardening:** `MeshRouter` no longer treats mutable `hops` membership as a forwarding suppression proof. A node relays a frame at most once using local per-frame deduplication; TTL remains the bounded hop-depth limit. `nextHopMetadata` normalizes the current node to the final hop entry and decrements TTL only if that relay was not already listed, preserving the MeshFrame v6 invariant `ttl + hops.size == MAX_TTL + 1`. The regression supplies a structurally valid frame with attacker-controlled hop metadata, verifies that the relay still forwards, and confirms that no route to the fabricated victim is learned.
- **Offline-library data preservation:** local catalogue metadata corruption puts the library into a read-only recovery state without pruning blobs; invalid remote advertisements remain individually isolated. Corrupt/missing blobs are preserved for explicit removal or same-content re-publish. Over-quota catalogues retain all valid rows and blobs; explicit owner removal restores write capacity. Orphan `.blob` files from a crash between blob and index commits are retained and can be re-indexed by publishing the same content. Physical blob bytes are included in capacity checks.
- **Wallet chunk confidentiality:** partial incoming voucher and acknowledgement chunks previously held in a separate plaintext preference are now migrated into the per-wallet AES-GCM / Android Keystore store. The old plaintext preference is deleted only after encrypted persistence succeeds. Pending envelope restoration validates count, ID, timestamp, prefix, chunk index/length, TTL and total size; malformed records fail closed.
- **Design-only product gates:** `docs/DISTRIBUTED_GOVERNANCE_REPUTATION_MARKETPLACE_ARBITRATION.md` specifies prerequisites, state machines, abuse cases and acceptance tests for issues #20, #21, #23 and #26. These remain unimplemented; the document explicitly states that a shared group credential is not a Sybil-resistant account identity and that the private IPv4 overlay is not Internet sharing.
- **CI corrections and outcome:** the workflow uses separate shell-safe PR-targeted and manual-full-suite emulator steps, passes `MESHNET_PREBUILT_WEB_ASSETS=true` to reuse the tested web bundle, disables cancel-in-progress and retains failure diagnostics. Run #179 reported all 187 focused API 36 tests passing; a later source audit found shared SQLite state in the simulated sender/receiver membership-sync fixture, so that specific test result needs revalidation after fixture isolation. The manual full Android suite remains unrun.

## Current targeted validation status — 2026-11-28

- **Final application/test-code validation — run #179:** tested commit `798d12bebdcb8cb365c1cbd52d60552d5e431570`; GitHub Actions run `46103437101`. Node 22 typecheck and production web build passed; Android `:app:assembleDebug` passed; APK signature verification passed; API 36 focused instrumentation reported **187/187 tests, 0 failures, 0 errors, 0 skipped** (the membership-sync test fixture was later found to share one SQLite database between both simulated peers; its independent-sync coverage requires a rerun after fixture correction), in 59.251 seconds. Both workflow jobs completed successfully. The branch head was subsequently advanced by documentation-only commits; no app or test source changed after the validated SHA.
- Run #178 also passed 187/187 on the preceding code/test commit. Run #179 added route diagnostic assertions and repeated the full focused class list on the final application/test-code SHA.
- Earlier failures were resolved in sequence: #154 exposed Kotlin source compilation errors in group delivery/membership; #156 exposed an emulator wrapper shell issue; #162 exposed a missing prebuilt-web-assets flag; #172 exposed a duplicate test method; #173 then executed the tests and exposed the invalid P-256 parameter check; #174 reduced the failures to two; #175 and #176 isolated the final route failure to inconsistent TTL/hop metadata; #177 confirmed that the first TTL normalization alone was insufficient. The final route fix keeps the MeshFrame v6 `ttl + hops.size` invariant when the current relay was already present in untrusted metadata, while forwarding suppression remains based on local frame deduplication rather than hop-list membership. The corrected regression now passes in #179.
- The test-only suite covers 22 targeted Android test classes (187 cases), not the full Android instrumentation suite. The manual full-suite workflow option has not been run as part of this batch.
- WebView origin restrictions, wallet AES-GCM/Keystore encryption (including pending chunks), membership rowid cursor paging and legacy v6 compatibility, route-loop/metadata handling, and non-destructive offline-library recovery are implemented with targeted regression coverage.
- **Still not complete:** issues #17–#28 remain open where product acceptance requires more than this CI batch. In particular #20 governance, #21 reputation, #23 public Internet gateway/marketplace and #26 arbitration are design-only; #18 per-device private-session E2EE/ratchet and independent cryptographic audit; #19 protocol-v7 group receipt/router/outbox integration; #22 private file ACL/search/backup; #24 receiver-measured billing and live receipt/wallet settlement; #25 global offline double-spend reconciliation and fiat settlement; encrypted export/restore; and physical multi-device radio/OEM validation remain open.
- **Verification boundary:** a passing API 36 emulator suite and debug APK do not prove physical BLE/Wi-Fi interoperability, background/OEM reliability, production security, independent cryptographic or smart-contract audit, legal approval or real-money settlement. No issue is closed by this CI result; keep PR #29 in Draft until remaining acceptance gates have implementation and evidence.


## Post-merge test-fixture audit — 2026-11-28

- PR #30 brought an optional database-name parameter to `MeshGroupMembershipStore` and isolated the sender/receiver databases in its membership-sync tests. The completion branch had not yet carried that adjustment: `MeshMembershipSyncProtocolTest` opened both simulated peers against the same SQLite file, which could let the receiver observe sender rows without protocol transfer.
- Corrected the completion branch to use distinct sender and receiver database files and added an assertion that the receiver starts empty before synchronization. This makes the test exercise the actual export/chunk/import path rather than shared local persistence.
- The historical run #179 remains recorded as a successful execution on its exact SHA, but its membership-sync test is not accepted as proof of independent peer synchronization. The new PR synchronization run must validate the corrected fixture before that coverage is considered CI-verified.
- No production protocol or stored database schema changed in this correction; the database-name parameter defaults to the existing production database name.


## Current head after mainline synchronization — 2026-11-28

- Branch head: `5284faf8880040b8702ecac9afba5438562e1ecc`; this is a two-parent merge commit that includes current `main` at `a5aecaab8af0f33ab161fba3c57c29cdda606fe7`. The branch is now 0 commits behind `main` and the merge base is current `main`.
- The membership sync fixture now uses separate sender and receiver databases and asserts the receiver is empty before the protocol exchange. The legacy partial-history test from mainline was retained alongside the paged-sync coverage.
- The current branch workflow is aligned with PR #30's failure diagnostics and API 36 test configuration, while keeping `cancel-in-progress: false` so a new push cannot silently cancel a long emulator run.
- The prior #179 result (187/187) predates the isolated-store correction and is historical only. The current branch has one additional partial-history test; CI must report and validate the actual current count before membership sync is marked CI-verified.
- No fresh Actions run is visible for this merge commit yet. Do not treat source review or the previous run as a substitute for the pending CI evidence.
