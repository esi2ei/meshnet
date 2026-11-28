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
| [#17](https://github.com/esi2ei/meshnet/issues/17) | Offline discovery and multi-hop routing | `MeshBleTransport.kt`, `MeshWifiDirectTransport.kt`, `MeshWifiAwareTransport.kt`, `MeshRouter.kt`, `MeshRouteRegistry.kt`, `MeshIpPacketRouter.kt` | Partial | Automatic cross-transport discovery/convergence, authenticated route control plane, loop/resource-abuse tests, physical multi-phone relay and Android background/OEM evidence |
| [#18](https://github.com/esi2ei/meshnet/issues/18) | Device identity and end-to-end security | `MeshDeviceIdentity.kt`, `MeshCrypto.kt`, `MeshSecretStore.kt`, `MainActivity.kt`, `MeshWebViewSecurityPolicy`; external WebView subresources and navigations are rejected and CSP disallows frames | Partial; the WebView/native bridge boundary is hardened for bundled assets, but shared group-key encryption remains | Per-device private-session encryption, audited ratchet/forward secrecy, secure group key distribution/rotation, onboarding/fingerprint verification, device export/recovery/revocation, downgrade/replay tests and independent audit |
| [#19](https://github.com/esi2ei/meshnet/issues/19) | Messaging, groups and multimedia | `MeshRouter.kt`, `MeshQueueStore.kt`, `MeshGroupMembershipStore.kt`, `MeshMembershipSyncProtocol.kt`, `MeshGroupDeliveryLedger.kt`, `MeshFileTransfer.kt`, `MeshLiveVoiceCall.kt`, `PhoneWorkspace.tsx`; signed JOIN/LEAVE log and snapshot-bounded rowid cursor pages over v6 ephemeral frames | Partial; membership sync now pages past 64 records within a fixed rowid snapshot and retries idempotently, but cursor state is in-memory/best-effort rather than crash-resumable; snapshots/receipts remain local primitives with no router/outbox/UI integration; broadcast ACK remains fail-closed | Persist sync checkpoints across process death; bind events/snapshots/receipts into protocol v7; integrate receipt transport and verification with router; atomically bind message creation to snapshot/outbox; retire only when every snapshot recipient reaches terminal state; adversarial + 3-device physical tests; enforce public/group/direct ACLs, private one-to-one/group E2EE, edit history/deletion/expiry, video calls and offline store-and-forward privacy/retention |
| [#20](https://github.com/esi2ei/meshnet/issues/20) | Public-channel governance | No complete governance implementation identified | Not implemented | Deterministic eligibility/quota/voting/quorum/tie/inactivity rules, manager suspension/replacement, Sybil resistance, audit trail and offline governance tests |
| [#21](https://github.com/esi2ei/meshnet/issues/21) | Reputation and trust | Device-origin signatures are not a reputation system | Not implemented | Verifiable endorsements, abuse-resistant scoring/decay, reports/evidence/right of response/appeal, Sybil and collusion protections, privacy analysis and adversarial tests |
| [#22](https://github.com/esi2ei/meshnet/issues/22) | Distributed file storage and search | `MeshOfflineLibrary.kt`, `MeshFileTransfer.kt`, `MeshOfflineLibraryTest.kt`; restored missing catalogue serializer after auto-build diagnostics | Partial; group-visible only; current-head build not verified | Private encrypted content and metadata, authorized discovery, bounded multi-hop search, owner quotas/revocation/expiry, encrypted peer backups, crash/recovery and abuse tests |
| [#23](https://github.com/esi2ei/meshnet/issues/23) | Data metering, offers and Internet sharing | Current VPN is a private `100.64.0.0/10` intranet; no gateway/NAT or metered marketplace | Not implemented | Byte-accurate payload accounting, free/fixed/negotiated offers, equal public rate, explicit acceptance, relay splits/consent, cheapest acceptable route, isolated permissioned Internet gateway and legal/carrier review |
| [#24](https://github.com/esi2ei/meshnet/issues/24) | Signed contracts and fair settlement | `MeshServiceContract.kt`, `MeshTransferReceipt`, and `MeshTransferReceiptStore.kt`: dual P-256 signatures, bounded terms, payload-byte pricing, capped cost, bounded full-chain validation, AES-GCM journal encryption via Android Keystore, atomic writes, legacy JSON migration and fail-closed missing-key/tamper handling; backup is disabled in the Android manifest | Partial; encrypted journal is still a storage primitive, not connected to offers, the receiver's accepted-payload boundary, wallet ledger or settlement; export/restore is absent | Integrate journal at the receiver's accepted-payload boundary; compute digest from actual accepted bytes; coordinate dual-signature exchange; reconcile with wallet idempotently; add crash/retry/no-double-billing integration tests, encrypted export/restore and independent cryptographic review |
| [#25](https://github.com/esi2ei/meshnet/issues/25) | Offline wallet and real-money controls | `MeshOfflineWalletStore.kt` now encrypts replay IDs, incoming history and outgoing vouchers with AES-GCM / Android Keystore and migrates legacy preferences; Devnet SOL remains a prototype | Partial; encrypted local records are not a global balance ledger and do not prevent offline double-spend or settle real money | Offline credit limits/double-spend reconciliation, pending/confirmed/reversed fiat ledger, provider integration, contract-linked receipts, budgets/caps/alerts/auto-stop, encrypted export/restore and recovery. Never label vouchers or Devnet SOL as settled fiat |
| [#26](https://github.com/esi2ei/meshnet/issues/26) | Peer arbitration and disputes | No complete arbitration workflow identified | Not implemented | Eligibility, verifiable random selection, quorum, timeout/replacement, evidence privacy, conflicts/collusion controls, appeal and deterministic settlement tests |
| [#27](https://github.com/esi2ei/meshnet/issues/27) | Android operation, local web companion and UX | Android runtime and bundled WebView dashboard; external subresource requests/navigations are rejected by `MeshWebViewSecurityPolicy`, and the local HTML uses a restrictive CSP | Partial | Continue authenticated bridge review; add explicit battery/data/storage/spending controls, accurate queue/relay/delivery/billing states, identity/data export-recovery, accessibility review and physical-device background/OEM validation |
| [#28](https://github.com/esi2ei/meshnet/issues/28) | Traceability, integration tests and staged delivery | This matrix, `RELEASE_TEST_PLAN.md`, `ARCHITECTURE.md`, encrypted receipt/wallet storage, WebView origin policy, paged membership sync and targeted instrumentation coverage | Partial | Run current-head target tests; link every acceptance criterion to implementation and passing evidence; add transfer/offer/wallet cross-feature integration and adversarial tests; preserve dependency-ordered delivery; report physical-device and independent-audit evidence separately |

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

- **Wallet at-rest security (source + test coverage):** `MeshOfflineWalletStore.kt` now uses AES-GCM with a non-exportable Android Keystore storage key. Replay IDs, incoming receipt history and outgoing signed vouchers are encrypted with key-specific authenticated data. Legacy string-set and JSON preferences migrate on first read; missing keys, unknown envelopes, authentication failures and malformed histories fail closed. These remain provisional offline vouchers, not a balance/settlement system.
- **WebView boundary (source + test coverage):** `MeshWebViewSecurityPolicy` only accepts HTTPS resources from the exact appassets host, without user-info or non-default ports, under `/assets/web/`. All other subresource requests receive a local 403 response rather than falling through to network access. The bundled dashboard CSP also denies frames and network connections. This reduces exposure of `addJavascriptInterface` to untrusted frames; it is not a substitute for an audited per-origin bridge or a private-session protocol.
- **Membership synchronization (source + test coverage):** `MeshGroupMembershipStore` exposes rowid high-watermarks and bounded pages. `MeshMembershipSyncProtocol` uses a fixed snapshot and monotonic cursor to synchronize histories larger than the former 64-event cap. Page chunks are verified and idempotent. Continuation state is currently in memory and best-effort; process-death resume, protocol-v7 binding, router/outbox atomicity and authoritative membership remain open.
- **Targeted tests authored:** `MeshOfflineWalletStoreTest`, `BundledDashboardAssetsTest`, `MeshMembershipSyncProtocolTest` and `MeshGroupMembershipStoreTest` were expanded. Tests for the encrypted receipt store and fail-closed ACK policy remain in the same target set. No test is marked passed until GitHub Actions reports a result for the actual head commit.
- **Remaining product-level scope:** issues #17–#28 are not all complete. Governance (#20), reputation (#21), real Internet sharing/marketplace (#23), peer arbitration (#26), protocol-v7 group receipt integration (#19), receiver-measured billing (#24), real-money settlement (#25), and physical-device/OEM evidence (#17/#27) still need substantive work and external/device validation. Do not advertise missing functionality as available.
- **Validation policy:** PR CI runs the focused Android API 36 instrumentation classes for wallet encryption, receipt journaling, membership pagination, WebView policy and router receipt handling, in addition to web checks and Android APK assembly. The full instrumentation suite remains a manual workflow option.
