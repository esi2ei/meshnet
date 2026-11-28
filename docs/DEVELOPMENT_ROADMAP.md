# MeshNet development roadmap

This document is the working source of truth for the completion branch. It distinguishes code present in main, CI-verified behavior, work in progress, and gates requiring physical devices or independent reviewers.

## Branch and delivery policy

- Stable baseline: `main` at merge commit `d3ecf7e5d734f577dc6920d4250b4597baeaa903` (PR #16).
- Trial artifact only: release/trial-apk (PR #15). Do not put feature work on this branch.
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
- **Targeted tests authored:** `MeshOfflineWalletStoreTest` covers encrypted history, migration, missing-key/tamper, malformed rows and encrypted pending chunks; `MeshOfflineLibraryTest` covers malformed metadata, over-quota preservation, orphan re-indexing and explicit repair; `MeshRouterReceiptTest` covers default-deny receipts and mutable-hop relay suppression; `MeshMembershipSyncProtocolTest` covers 70-event paging, immediate continuation and cursor-jump rejection; `MeshGroupMembershipStoreTest` covers stable snapshot pages. The PR test class filter also includes receipt, delivery-ledger, queue, route registry, IP router, file transfer, portal, peer registry, device identity, crypto, frame, contract, wallet-authorization and socket-policy tests. No test is marked passed until Actions reports a result for the actual head commit.
- **Remaining product-level scope:** issues #17–#28 are not all complete. Governance (#20), reputation (#21), real Internet sharing/marketplace (#23), peer arbitration (#26), protocol-v7 group receipt integration (#19), receiver-measured billing (#24), real-money settlement (#25), and physical-device/OEM evidence (#17/#27) still need substantive work and external/device validation. Do not advertise missing functionality as available.
- **Validation policy:** PR CI runs the focused Android API 36 instrumentation classes for wallet encryption, receipt journaling, membership pagination, WebView policy and router receipt handling, in addition to web checks and Android APK assembly. The full instrumentation suite remains a manual workflow option.

## Android CI compile triage — 2026-11-28

- Run #154 (commit `4718f976bda4c839f90e5b2ff205efe12bc541eb`) passed the web typecheck and production bundle but failed during Android Kotlin compilation before instrumentation. Compiler output identified `MeshGroupDeliveryLedger.kt` using `String` constants with `String.matches(Regex)` and calling a `sha256` helper that was scoped only inside the ledger class; `MeshGroupMembershipStore.kt` referenced a private companion constant from the top-level event model.
- **Source correction committed:** snapshot/receipt validation patterns are now `Regex` values; a file-scope SHA-256 helper is available to the snapshot and receipt data classes; the membership timestamp skew bound is file-scoped and accessible to event verification.
- **Wallet integrity follow-up:** malformed JSON rows are no longer skipped by `history()`, `outgoingHistory()` or `pendingOutgoingVouchers()`. Full receipt history now rejects a new voucher with a clear capacity error rather than deleting the oldest receipt. Regression-test source covers malformed legacy records.
- The workflow captures Android build and instrumentation logs as a diagnostic artifact on failure. The next PR run must validate the current head; run #154 is not a pass and no instrumentation ran on that attempt.

## Additional hardening and design gates — 2026-11-28

- **Routing loop/DoS hardening:** `MeshRouter` no longer treats the mutable `hops` field as proof that a frame has already visited the current relay. Relays are bounded by local per-frame deduplication and TTL; the forwarded metadata removes duplicate copies of the current relay and appends the relay as the latest hop. A regression test now injects the relay ID into attacker-controlled hop metadata.
- **Offline-library data preservation:** local catalogue metadata corruption puts the library into a read-only recovery state without pruning blobs; invalid remote advertisements remain individually isolated. Corrupt/missing blobs are preserved for explicit removal or same-content re-publish. Over-quota catalogues retain all valid rows and blobs; explicit owner removal restores write capacity. Orphan `.blob` files from a crash between blob and index commits are retained and can be re-indexed by publishing the same content. Physical blob bytes are included in capacity checks.
- **Wallet chunk confidentiality:** partial incoming voucher and acknowledgement chunks previously held in a separate plaintext preference are now migrated into the per-wallet AES-GCM / Android Keystore store. The old plaintext preference is deleted only after encrypted persistence succeeds. Pending envelope restoration validates count, ID, timestamp, prefix, chunk index/length, TTL and total size; malformed records fail closed.
- **Design-only product gates:** `docs/DISTRIBUTED_GOVERNANCE_REPUTATION_MARKETPLACE_ARBITRATION.md` specifies prerequisites, state machines, abuse cases and acceptance tests for issues #20, #21, #23 and #26. These remain unimplemented; the document explicitly states that a shared group credential is not a Sybil-resistant account identity and that the private IPv4 overlay is not Internet sharing.
- **CI corrections:** run #156 built and signed the debug APK successfully, but the emulator action split a multi-line conditional script and failed before JUnit instrumentation. The workflow now uses separate one-line PR-targeted and manual-full-suite shell commands. `cancel-in-progress` is disabled so follow-up pushes do not cancel a running validation. The next run is the first valid targeted-test attempt for this expanded set.

## Current targeted validation status — 2026-11-28

- Run #156 passed Node 22 web typecheck/build, Android debug APK assembly and APK signature verification, but the emulator action split a multiline `if/fi` script into separate shells and failed before invoking Gradle/JUnit. This was a workflow-wrapper failure, not a test assertion result.
- Run #162 also passed web typecheck/build, APK assembly and signature verification, then failed before JUnit because the emulator step lacked `MESHNET_PREBUILT_WEB_ASSETS=true`; Gradle attempted `vite build` inside the Android job, where npm dependencies were intentionally not installed (`vite: not found`). Instrumentation Gradle did not start in that attempt.
- The workflow now uses a single-line shell-safe command and passes `MESHNET_PREBUILT_WEB_ASSETS=true` into both focused and manual emulator steps, reusing the already-tested `dist` artifact. The next run on the updated PR head is the first attempt intended to execute JUnit tests. Build/test logs and reports are retained as failure artifacts.
- Route forwarding no longer uses attacker-controlled `hops` membership to suppress a relay. Local per-frame deduplication and TTL bound forwarding; forwarding normalizes the current node ID in the metadata. This is not a substitute for signed per-hop attestations or physical multi-device loop testing.
- Offline library recovery is non-destructive: malformed metadata blocks mutations, over-quota catalogs preserve all rows/blobs and permit explicit item removal, corrupt/missing blobs are surfaced for explicit removal or same-content repair, and unindexed blobs are retained/re-indexed after interrupted commits. New write capacity includes physical blob bytes.
- A targeted run on the final head is still required. Do not equate the #156 successful APK build with passing test assertions or real-radio/OEM evidence.
