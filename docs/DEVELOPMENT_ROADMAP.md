# MeshNet development roadmap

This document is the working source of truth for the completion branch. It distinguishes code present in main, CI-verified behavior, work in progress, and gates requiring physical devices or independent reviewers.

## Branch and delivery policy

- Stable baseline: main at merge commit 0fe421254c8b8e60ec0b03f064d5309bea278079 (2026-11-28; includes merged PRs #34 and #35).
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
| [#18](https://github.com/esi2ei/meshnet/issues/18) | Device identity and end-to-end security | `MeshDeviceIdentity.kt`, `MeshCrypto.kt`, `MeshSecretStore.kt`, `SECURITY_AND_ESCROW.md` | Partial; security gate open | Per-device private-session encryption, audited ratchet/forward secrecy, secure group key distribution/rotation, onboarding/fingerprint verification, device export/recovery/revocation, downgrade/replay tests and independent audit |
| [#19](https://github.com/esi2ei/meshnet/issues/19) | Messaging, groups and multimedia | `MeshRouter.kt`, `MeshQueueStore.kt`, `MeshGroupMembershipStore.kt`, `MeshMembershipSyncProtocol.kt`, `MeshGroupDeliveryLedger.kt`, `MeshFileTransfer.kt`, `MeshLiveVoiceCall.kt`, `PhoneWorkspace.tsx`; signed JOIN/LEAVE log, bounded v6 ephemeral chunk sync, immutable sender-signed snapshots and per-member signed receipt ledger | Partial; local membership events sync best-effort (latest 64 only) on peer connection; snapshots/receipts remain local primitives with no router/outbox/UI integration; no outbox+snapshot atomic transaction, encrypted-at-rest storage or global roster guarantee; broadcast ACK remains fail-closed | Add paged crash-resumable membership reconciliation and stale-roster UX; bind events/snapshots/receipts into protocol v7; integrate receipt transport and verification with router; atomically bind message creation to snapshot/outbox; retire only when every snapshot recipient reaches terminal state; adversarial + 3-device physical tests; also enforce public/group/direct ACLs, private one-to-one/group E2EE, edit history/deletion/expiry, video calls and offline store-and-forward privacy/retention tests |
| [#20](https://github.com/esi2ei/meshnet/issues/20) | Public-channel governance | No complete governance implementation identified | Not implemented | Deterministic eligibility/quota/voting/quorum/tie/inactivity rules, manager suspension/replacement, Sybil resistance, audit trail and offline governance tests |
| [#21](https://github.com/esi2ei/meshnet/issues/21) | Reputation and trust | Device-origin signatures are not a reputation system | Not implemented | Verifiable endorsements, abuse-resistant scoring/decay, reports/evidence/right of response/appeal, Sybil and collusion protections, privacy analysis and adversarial tests |
| [#22](https://github.com/esi2ei/meshnet/issues/22) | Distributed file storage and search | `MeshOfflineLibrary.kt`, `MeshFileTransfer.kt`, `MeshOfflineLibraryTest.kt`; restored missing catalogue serializer after auto-build diagnostics | Partial; group-visible only; current-head build not verified | Private encrypted content and metadata, authorized discovery, bounded multi-hop search, owner quotas/revocation/expiry, encrypted peer backups, crash/recovery and abuse tests |
| [#23](https://github.com/esi2ei/meshnet/issues/23) | Data metering, offers and Internet sharing | Current VPN is a private `100.64.0.0/10` intranet; no gateway/NAT or metered marketplace | Not implemented | Byte-accurate payload accounting, free/fixed/negotiated offers, equal public rate, explicit acceptance, relay splits/consent, cheapest acceptable route, isolated permissioned Internet gateway and legal/carrier review |
| [#24](https://github.com/esi2ei/meshnet/issues/24) | Signed contracts and fair settlement | `MeshServiceContract.kt`, `MeshTransferReceipt`, and `MeshTransferReceiptStore.kt` provide dual P-256 signatures, bounded terms, payload-byte pricing, capped cost, and a durable bounded journal that verifies full chains from genesis and treats exact retries idempotently | Partial; journal primitive exists but is not connected to offers, actual receiver byte accounting, wallet ledger or settlement | Integrate journal at the receiver's accepted-payload boundary; compute digest from actual accepted bytes; coordinate dual-signature exchange; reconcile with wallet idempotently; add crash/retry/no-double-billing integration tests and independent cryptographic review |
| [#25](https://github.com/esi2ei/meshnet/issues/25) | Offline wallet and real-money controls | `MeshOfflineWalletStore.kt`, `MeshWalletAuthorizationStore.kt`, Devnet SOL prototype | Partial; no real-money settlement | Offline credit limits/double-spend reconciliation, pending/confirmed/reversed fiat ledger, provider integration, contract-linked receipts, budgets/caps/alerts/auto-stop and encrypted backup/recovery. Never label vouchers or Devnet SOL as settled fiat |
| [#26](https://github.com/esi2ei/meshnet/issues/26) | Peer arbitration and disputes | No complete arbitration workflow identified | Not implemented | Eligibility, verifiable random selection, quorum, timeout/replacement, evidence privacy, conflicts/collusion controls, appeal and deterministic settlement tests |
| [#27](https://github.com/esi2ei/meshnet/issues/27) | Android operation, local web companion and UX | Android runtime and bundled WebView dashboard; `MainActivity.kt`, `PhoneWorkspace.tsx`, `MeshForegroundService.kt` | Partial | Authenticated local companion boundary review, explicit battery/data/storage/spending controls, accurate queue/relay/delivery/billing states, identity/data export-recovery and physical-device background/OEM validation |
| [#28](https://github.com/esi2ei/meshnet/issues/28) | Traceability, integration tests and staged delivery | This matrix, [RELEASE_TEST_PLAN.md](RELEASE_TEST_PLAN.md), `ARCHITECTURE.md`, `MeshTransferReceiptStore.kt`, `MeshTransferReceiptStoreTest.kt`, existing instrumentation tests | Partial | Link every acceptance criterion to implementation and regression evidence; execute builds/tests only when authorized; add transfer/offer/wallet cross-feature integration and adversarial tests; preserve dependency-ordered delivery; report CI/device/audit evidence separately |

### Historical verification boundary — earlier membership/delivery-ledger batch (not the current CI policy)

- The user explicitly requested that CI not be run. No workflow was manually dispatched. Membership, sync and delivery-ledger commits use `[skip ci]`; no local build or instrumentation test has been executed for this batch. GitHub nevertheless automatically queued PR workflow run #138 after branch synchronization; Node 22/24 typecheck and web production-build jobs completed, while the Android job was still in progress at last inspection. This was unintended, and the connected GitHub actions do not expose cancellation.
- No local build or test was manually executed for the current membership and delivery-ledger additions. The older automatic run #138 and the previously successful run #153 predate these additions and do not validate them.
- The earlier documentation correction was committed with the GitHub Actions skip marker to avoid another automatic run at that time. This is a historical statement; it does not describe the current verification policy.
- GitHub issues remain open until their own acceptance criteria have implementation and evidence. This batch does not convert deferred physical-device or independent-audit requirements into completed work.
- The current VPN is a private overlay/intranet only. Internet gateway/NAT, public Internet resale, metered sharing and automatic cheapest-provider selection are not implemented.

## Incremental audit fixes — 2026-11-28

- **Route poisoning mitigation (partial):** route learning now records only the cryptographically verified frame origin through the actual ingress peer. The mutable hops list is no longer trusted as a route proof. This prevents a fabricated hop list from teaching a route to an unrelated victim. Per-hop signed path attestations, authenticated peer sessions, and stronger loop/TTL guarantees remain open. Forwarding is also limited to one relay per node per frame; mutable hop counts no longer influence duplicate-forwarding decisions.
- **Outbox data preservation:** the outbox no longer deletes the oldest rows to satisfy its 6,000-entry cap. A full queue rejects the new enqueue with false (backpressure) and preserves existing unacknowledged frames. Automatic outbox age-based deletion was removed; frame/replay retention is now 30 days. User-visible expired-message management and per-member group delivery receipts remain open.
- **IPv4 correctness:** incoming overlay packets now require a valid IPv4 header checksum. Regression-test packet builders generate checksums and a corrupted-header test is included.
- **Address collision recovery (partial):** remote address-conflict records expire after the same 180-second claim TTL, allowing fresh announcements to be learned. A collision with this device's own stable address remains fail-closed; dynamic address leases/reassignment and deterministic collision-free allocation remain open.
- **CI cost controls:** the duplicate Node 24 matrix job was removed (Node 22 remains); setup-node caches npm dependencies using package.json as the current cache dependency path; the tested web dist artifact is shared with Android packaging; concurrency cancels superseded runs; successful PR runs retain only the web artifact for one day, while Android diagnostics are uploaded on failure or manual dispatch. Pull-request CI runs the Node 22 typecheck/web build, Android debug APK assembly/signature verification, and the focused Android API 36 emulator instrumentation suite. Only the full Android instrumentation suite is reserved for manual workflow_dispatch. **Deterministic-install gap:** there is no npm lockfile and CI currently uses npm install, not npm ci; a reviewed lockfile and lockfile-keyed cache remain open under issue #31.

**Verification boundary:** these changes have been committed to the existing PR branch. Source-level review and regression-test source updates are present, but no claim of passing build or instrumentation is made until the single consolidated PR workflow run reports results. Physical-device checks and external security audits remain separate gates.

## Verification update — 2026-11-28

This section records the live repository state after the PR audit; it supersedes the historical verification notes above where they describe the current CI policy.

### Existing pull requests

- **PR #2 — Mobile Wallet Adapter 2.2.1:** remains Draft/deferred. Exact-head run #53 failed Android :app:checkDebugAarMetadata: the dependency requires compileSdk 37 / AGP 9.1+, while the branch uses API 36 / AGP 8.9.1. Compared with main at `0fe421254c8b8e60ec0b03f064d5309bea278079`, its branch is 2 commits ahead and 144 commits behind. Do not merge this dependency bump independently; revisit only as part of a coordinated toolchain migration.
- **PR #29 — default-deny unverified receipts:** remains Draft. Current exact-head run #237 on `c41e48dfbc89c63619776baf1ba6c4503bee7259` passed the Node 22 bridge contract/typecheck/web build, Android debug APK assembly/signature verification and **197 focused API 36 emulator tests**. The full Android instrumentation suite was skipped (manual workflow dispatch only), and physical-device checks remain Pending / Not Run. This does not close its product/security gates: per-device E2EE and ratchets/forward secrecy, key enrollment/revocation/recovery, protocol-v7 durable group receipt and router/outbox/UI integration, crash-resumable membership sync, private file controls, and independent cryptographic review.
- **PR #34 — VPN packet-drop diagnostics:** merged as 48a2a5f578bffb0e2a6cf82532f1052bc70c5d9d. Exact-head run #232 passed 169 focused API 36 tests (0 failed / 0 skipped), Node/web build, APK assembly and signature/checksum validation. It adds a cumulative, rate-limited diagnostic when the mesh does not fully accept a packet from the VPN TUN. It does not implement durable packet queueing, sender backpressure, fragment retry or retransmission; issue #32 remains open.
- **PR #35 — immutable GitHub Actions references:** merged as 0fe421254c8b8e60ec0b03f064d5309bea278079. Synchronized-head run #234 passed Node 22, Android APK/signature checks and 169 focused API 36 tests (0 failed / 0 skipped). All 12 uses occurrences in the workflow now pin full upstream commit SHAs. GitHub did not mark the pinned android-actions/setup-android and reactivecircus/android-emulator-runner commits as verified; SHA pinning prevents tag drift but does not establish their signature provenance.

### Test-status policy

- **GitHub CI:** run the checks configured in the workflow on the exact PR head. Focused API 36 emulator instrumentation is part of every PR workflow. The complete Android instrumentation suite is a manual workflow option and was Not Run in runs #231, #232, #233 and #234.
- **Physical-device/lab tests:** BLE/GATT, Wi-Fi Direct/Aware, multi-hop relay, OEM background restrictions, VPN/DNS handover, interrupted file-resume integrity, audio lifecycle and physical performance baselines remain **Pending / Not Run**. The exact test IDs, device prerequisites, manual steps and pass criteria are in [RELEASE_TEST_PLAN.md](RELEASE_TEST_PLAN.md). Record device model, OEM build, Android API, topology, logs and measured outcome before changing status.
- Do not describe emulator tests as physical-device evidence, and do not mark manual-only or hardware tests as passed without results.

### CI, artifact and repository controls

- The PR workflow is triggered by pull_request and workflow_dispatch; it currently has no push-to-main trigger. A separate post-merge run is therefore not automatically created. For PR #34, the tested head and merge commit have identical Git tree SHA 8e6c7767337ac6f76994f92223fad60a9a58f15c; run #232 validates that exact merged source tree.
- Successful runs #231–#234 retained only the web dashboard artifact with one-day retention (observed sizes: 335,831; 284,277; 283,172; and 284,277 bytes respectively). APK and Android test-report artifacts are not retained on successful PR runs. These sizes are observations, not a measured saving; no comparable before/after baseline was available.
- The current main branch is reported unprotected and no repository rulesets were returned. The connected GitHub integration cannot administer repository branch protection. The repository owner must configure a main ruleset requiring the relevant CI checks and preventing force-push/deletion.
- Dependabot is configured weekly for GitHub Actions, npm, Gradle and Cargo. No package-lock.json, pnpm lock or Yarn lock exists in main; CI uses npm install. Generating and reviewing a lockfile, switching to npm ci, and caching from the lockfile are still required for deterministic installs.
- Dependabot, Code Scanning and Secret Scanning alert collections were not readable through the connected integration. No statement that these alert queues are clear is made.

### Release and security boundary

The repository SECURITY.md explicitly describes MeshNet as a prototype not to be relied on for confidential, safety-critical or financial traffic. Shared group AES-GCM does not provide authenticated per-device identity, forward secrecy or secure group-key rotation; the wallet flow is Devnet-only and no real payment or escrow settlement is implemented. Debug APK/emulator results are not production security certification. Production signing material, signed release AAB, physical-device matrix, independent cryptographic review, and legal/store decisions remain open.
