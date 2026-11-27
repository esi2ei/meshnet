# MeshNet assistant memory

Last reviewed: 2026-11-27
Repository: esi2ei/meshnet
Working branch: fix/meshnet-ci-vite-20261127

## Owner instructions

- Continue work on the existing fix/meshnet-ci-vite-20261127 branch unless the owner explicitly changes that instruction.
- Do not run or dispatch GitHub Actions CI until the owner says the program is complete and authorizes validation. Source edits and commits must not trigger CI automatically.
- Never claim a build, instrumentation test, physical-radio test, security audit, deployment, or payment settlement passed unless it was actually run and independently verified.
- Keep credentials, passphrases, keystores, wallet secrets, and program keypairs outside the repository.

## Branch state at review

- Reviewed head before implementation: 3f00d5e8fbde1fc8222421e8a2a650a9a1b6de53 (2026-11-27).
- Implementation commit: 8cff55018cafd4907799fd8f408f73bdc383c310; this is the new branch head at the time of this memory update.
- Before implementation the branch was 137 commits ahead of main and 0 behind; after the implementation commit it is 138 commits ahead and 0 behind. Merge base: e8a73a773797f36c0e6fde3d8e096f0e93b79b4d.
- The branch was present and unprotected.
- No assistant-memory branch and no memory/ESI_ASSISTANT_MEMORY.md file existed in this repository at review time. This memory is being kept on the owner's requested working branch so work remains on one branch.
- No open pull requests or issues were found in the repository during review.
- The workflow .github/workflows/webpack.yml is manual-only (workflow_dispatch). Do not dispatch it without explicit authorization.
- The latest run visible during final verification was run 51, successful, on older SHA 0385f4f342f94fa760337510cbd5675139cb7aad; it does not validate the current branch head. Runs 50 and 49 were failed/cancelled on other older SHAs. No CI was started during this work.

## Implemented product scope

MeshNet is an offline-first Android peer-to-peer mesh messaging prototype with a React/Vite dashboard bundled into the APK. Native scope includes BLE/GATT, optional Wi-Fi Aware, user-controlled Wi-Fi Direct, AES-GCM group frames, persistent outbox/replay state, bounded resumable encrypted file transfer, voice notes, opt-in experimental live group voice, and a signed offline local-unit voucher/acknowledgement prototype. Optional Solana Mobile Wallet Adapter flow is limited to user-approved native SOL transfers on Devnet.

The topology map in the browser is a simulator, not a real radio scan. The Android native runtime panel is the real device integration.

## Work completed in this review

- Rechecked the working branch, head, tree, recent commits, branch comparison, workflow definition, and current source/test layout.
- Added a protocol guard that requires the transport chunk-envelope ID to match the signed voucher ID or signed acknowledgement's voucher ID.
- Tightened chunk framing: non-final chunks must be exactly 48 characters and every chunk must be non-empty and no larger than 48 characters.
- Added instrumentation test cases for matching IDs, mismatched payment IDs, mismatched acknowledgement IDs, and empty IDs.
- CI has not been run. The new tests are source changes only and are not reported as passing until executed. Current head 8cff55018cafd4907799fd8f408f73bdc383c310 is not covered by the previously observed run 51.

## Remaining work and release blockers

### Must be completed before production claims

1. Integrate a reviewed per-device authenticated cryptographic protocol (the documented candidate is vodozemac/Olm + Megolm behind a narrow native boundary); add signed prekeys, authenticated session establishment, forward secrecy, secure group-key distribution/rotation, revocation/recovery, encrypted persistent ratchet state, version negotiation, and interoperability/tamper/replay tests. Current shared group AES-GCM is not Signal Protocol and does not provide authenticated per-device identity or forward secrecy.
2. Replace broadcast-style relay with loop-safe, expiry-aware, destination-aware routing and measured link-quality selection; current forwarding is broadcast-style with bounded duplicate suppression and hop/arrival-age scoring.
3. Validate BLE bidirectional sending/receiving on physical phones, including advertiser/scanner role combinations, reconnect, MTU fragmentation, write failures, congestion/backoff, and service lifecycle.
4. Validate three-phone multi-hop relay with origin and destination outside direct range; verify duplicate suppression, TTL, and return-path acknowledgements.
5. Validate Wi-Fi Direct and Wi-Fi Aware interoperability, coexistence, discovery, reconnect, and fallback on supported physical devices.
6. Validate file transfer at boundary sizes and 10 MiB, interruption/restart, resume, cancellation tombstones, SHA-256 integrity, quotas, URI permission expiry, and export.
7. Validate voice notes and live voice on physical devices: codec availability, audio focus, echo, packet loss, latency, microphone foreground-service restrictions, headset routing, and battery.
8. Build/test the Solana Anchor escrow program reproducibly, add adversarial/integration tests, verify program/admin key ownership, deploy only to an explicitly approved test cluster, obtain independent audit, and define upgrade authority/emergency policy. It is not integrated or approved for real funds.
9. Validate Devnet wallet authorization, signing, serialization, account binding, RPC failures, signature confirmation, and recipient balance independently. No Mainnet, SPL-token, or escrow transfer is implemented.
10. Complete release signing with a keystore held outside the checkout, verify AAB signer and checksum, complete store/legal/privacy disclosures, and choose a release version only with owner approval.

### Manual/owner-dependent

- At least two real BLE-capable Android phones and preferably a third relay phone are required for radio interoperability validation.
- A compatible Devnet wallet and internet connection are required for wallet-flow validation.
- Release keystore, signing credentials, real Solana program/admin public keys, and any deployment/audit approvals must be supplied/confirmed by the owner.
- iOS remains deferred by product direction; do not start an iOS port unless asked.

## Validation policy

- Local source inspection is not a build.
- Emulator instrumentation is not physical BLE/Wi-Fi interoperability.
- A successful CI run is not a cryptographic audit, smart-contract audit, or release approval.
- Do not use the offline MESH-LOCAL-UNIT receipts as money, cryptocurrency, spendable balance, or settlement.
- Do not accept or transfer real funds through the unbuilt/unaudited escrow prototype.
