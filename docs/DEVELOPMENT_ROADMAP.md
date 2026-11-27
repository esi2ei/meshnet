# MeshNet development roadmap

This document is the working source of truth for the completion branch. It distinguishes code present in main, CI-verified behavior, work in progress, and gates requiring physical devices or independent reviewers.

## Branch and delivery policy

- Stable baseline: main at merge commit aff4890db7782cf2d1090ac9fe65095eb064113b.
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
