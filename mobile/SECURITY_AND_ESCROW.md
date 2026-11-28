# Security, cryptography, and escrow implementation decisions

This document records an implementation decision, not a claim that the integrations below are already active.

## 1. Cryptography: use vodozemac instead of libsignal

**Selected candidate:** [matrix-org/vodozemac](https://github.com/matrix-org/vodozemac), Apache-2.0, Rust crate. The inspected release is 0.11.2 (Rust edition 2024; declared MSRV Rust 1.96). It implements Olm (Double Ratchet) and Megolm, and its maintainers publish a security audit by Least Authority. Pin the exact release and commit the dependency lockfile when integrating. The Android NDK/JNI build must use a compatible Rust toolchain; this repository's current Android setup does not yet provide that toolchain.

Why this is a better licensing fit than libsignal for this repository:
- Apache-2.0 permits commercial redistribution subject to its license, notice, and attribution conditions.
- The upstream project is actively maintained and has a published audit.
- It avoids adopting Signal's AGPL licensing and Signal's explicit warning that use outside Signal clients is unsupported.

Important engineering caveat: vodozemac is a Rust library, not a drop-in Android/Kotlin SDK. The separate `matrix-org/vodozemac-bindings` repository is marked unmaintained. Do not add that abandoned binding as a production dependency. The Android integration must own a small, reviewed JNI/UniFFI boundary and keep all ratchet state inside the Rust implementation.

Required integration sequence:
1. Add a Rust workspace crate with a narrow C ABI/JNI boundary; pin the Rust toolchain and exact vodozemac release.
2. Implement device identity keys, signed one-time prekeys, authenticated session establishment, per-device Olm sessions, and group-session distribution. Never derive a long-lived group key directly from a user passphrase.
3. Persist encrypted/pickled account and session state using Android Keystore-wrapped storage keys; bind records to the local device identity and handle rollback/corruption explicitly.
4. Define key rotation, device revocation, lost-device recovery, and protocol-version negotiation before switching message encryption.
5. Add cross-device interoperability vectors, tamper/replay tests, state-restoration tests, dependency/license inventory, and an independent cryptographic review.
6. Only then migrate messages. Protocol v4 group AES-GCM remains the current legacy mode until all devices have upgraded; do not silently reinterpret old ciphertext as ratchet ciphertext.

**Current state:** vodozemac remains a selected Apache-2.0 replacement candidate; it is not integrated into the Android runtime. The Android app now has a stable per-install P-256 signing key in Android Keystore. The node ID is derived from the public-key fingerprint, and protocol v6 carries the public key plus an ECDSA signature over the immutable plaintext digest/message identity. The receiver verifies the origin signature after AES-GCM decryption, while relays preserve the origin signature when re-encrypting hop/TTL metadata. This authenticates the origin signing key, but it does not change the encryption model: the group still shares one AES-GCM key, and all group members with that key can decrypt group traffic. There are no per-device Olm sessions, ratchets, forward secrecy, signed prekey bundles, explicit device approval/revocation, or secure group-key rotation. No Rust JNI/UniFFI module, native build, key bundle publication, or ratchet-session persistence has been implemented. Independent review of the custom identity/signature integration is still required.

## 2. Escrow: non-custodial Solana PDA escrow; no custodial server wallet

**Selected starting point for code study only:** [Solana Foundation escrow program](https://github.com/solana-foundation/escrow), MIT-licensed. Its audit status file identifies Accretion's audited-through commit as `36187ad52c7c03d11b13b6f1da9461f2f757cee2` (2026-04-07); the current `main` is 106 commits ahead of that baseline, so the current head is **not covered by that audit**. Its published deployment is Devnet only. It is a receipt-based deposit/withdrawal program; it is not, by itself, a marketplace contract that releases payment to a service provider after delivery or resolves buyer/provider disputes. Therefore, neither the current upstream head nor its Devnet deployment is approved here for real funds.

For MeshNet bandwidth or service payments, the target design is:
- USDC SPL token only, with the canonical mint explicitly pinned per cluster.
- One isolated PDA vault and immutable terms per order/session; never a shared hot wallet.
- Explicit payer, provider, amount, expiry, fee cap, and dispute authority in the order state.
- A two-party signed completion or a clearly identified arbiter decision before release; payer-only refund after expiry when no valid completion exists.
- Checked integer arithmetic, token-program and mint validation, associated-token-account validation, replay protection, pause/emergency procedures, and no arbitrary CPI hooks.
- Fee collection separated from user escrow principal; multisig + timelock for any administrative authority.
- Client verifies the program ID, cluster genesis/network, mint, PDA derivation, transaction simulation, and finalised transaction signature before showing a settled status.

The Foundation's DvP program is another audited option for atomic exchange of two token/asset legs with a named settlement authority. It is appropriate for swaps, not a substitute for a reviewed delivery/dispute state machine for bandwidth metering. Any adoption must independently pin the deployed program ID, audited commit, audit scope, and upgrade authority; a public claim that a program was audited is not evidence that the current deployed binary matches the audited source.

### Safe activation gates

Do not accept or display real deposits until all of these are available and reviewed:
1. A deployed program ID and reproducible build hash for the exact audited commit on the intended cluster.
2. The full audit report and a documented diff showing that deployed code matches the audited scope; no unreviewed code delta.
3. Canonical USDC mint, token program, RPC/cluster identity, and upgrade-authority policy pinned in release configuration.
4. A multisig/timelock or immutable program policy, emergency pause/recovery plan, and documented dispute authority.
5. Adversarial program tests for unauthorized release/refund, replay, wrong mint, wrong vault, arithmetic overflow, expiry boundaries, account substitution, and frozen-token behavior.
6. External security review and explicit product/legal approval for fees, custody characterization, disputes, and supported jurisdictions.

**Current state:** a first-pass Anchor escrow program source exists under `solana/programs/meshnet-escrow` with order-specific PDA vaults, immutable terms, buyer+provider release, buyer refund after expiry, and configured-arbitrator dispute settlement. Terminal order state is retained for both ordinary and arbitrated settlement; the buyer may explicitly close only a settled order to reclaim its rent. Vault balance and expiry arithmetic have explicit guards. Config initialization now hard-allows only Circle's canonical Devnet USDC mint under the default `devnet` Cargo profile; a mainnet build must explicitly disable defaults and enable the `mainnet` feature, which pins Circle's canonical mainnet USDC mint. Mixed cluster build features are rejected. The Rust build now requires `MESHNET_ESCROW_PROGRAM_ID` and `MESHNET_ESCROW_INITIAL_ADMIN` to be supplied as real public keys; no placeholder program/admin defaults remain. These are build-time identity inputs, not proof of key ownership or deployment. This source is not built, tested, deployed, audited, or integrated into the Android app. MeshNet cannot accept or transfer mainnet/USDC escrow funds; only separate Devnet test-SOL transfers are currently supported.

## 3. Link-quality implementation status

The Android router now consumes a bounded per-peer estimator. BLE scan callbacks feed RSSI samples; directed transport writes feed local acceptance/failure and elapsed-call latency samples. An exponentially smoothed delivery ratio, latency estimate, and optional RSSI are combined into a 0–100 score. Scores decay toward neutral after five minutes without a fresh sample, and disconnected peers are removed. The route registry combines hop distance with the measured score when ordering next-hop candidates. This does not yet measure end-to-end RTT, packet delivery at the remote application, sustained throughput, Wi-Fi RSSI for every OEM, or interference/channel occupancy. Device-specific calibration and multi-phone validation remain required.

## 4. Other outstanding work

| Area | Current state | Safe next step |
|---|---|---|
| File transfer | 10 MiB capped streaming sender, 16-chunk bounded windows, durable receiver checkpoints, streaming SHA-256 verification, terminal ACK only after verified finalization, cancellable sender workers, 24-hour cancellation tombstones, transfer-ID rotation after cancel, user-mediated export, 50-file / 100 MiB inbox quota, and acknowledged-byte / average-throughput progress are implemented in source | Validate cancellation/replay ordering, crash recovery, quota eviction, save/export behavior, throughput accuracy, and large-file interoperability on devices |
| Voice | User-controlled AAC voice notes and opt-in live group voice over encrypted v4 EPHEMERAL AMR-NB packets, per-origin jitter buffers/decoders, 20 ms frames, and bounded mixing are implemented in source | Validate codec behavior, acoustic echo, loss concealment, microphone foreground-service policy, latency, and battery use on devices |
| Wi-Fi Aware / Wi-Fi Direct | Optional Wi-Fi Aware discovery and PSK-negotiated TCP transport source is implemented alongside Wi-Fi Direct; device interoperability remains unverified | Validate Aware availability changes, multi-peer paths, coexistence with Wi-Fi Direct, and real-device interoperability |
| Congestion/routing | Per-peer BLE queue backoff/retry, ingress-excluding relay dispatch, and hop-first/arrival-age duplicate-route scoring are implemented in source; no destination-aware next-hop or radio signal-quality selection exists | Add route advertisements, measured link metrics, expiry-aware scheduling, bounded route expiry, and loop-safe unicast forwarding |
| Solana escrow | Anchor source includes terminal-state retention, buyer-only close-after-settlement, vault checks, and checked expiry arithmetic; not compiled, tested, deployed, or audited | Set real program/admin IDs after ownership confirmation, add adversarial/integration tests, reproducible build, external audit, deployment gates, and full transaction UX |
| Wallet | Android uses MWA 2.0.7 for Devnet authorization/deauthorization and user-approved native SOL transfers on Devnet. A bounded legacy SystemProgram transfer builder fetches a recent blockhash from Devnet RPC, submits through the wallet's optional MWA sign-and-send operation at confirmed commitment, and reads signature status back. Public key and auth token are stored encrypted with a non-exportable Android Keystore key and restored after process recreation. No mainnet, SPL-token, or escrow transfer is implemented; dApp identity URI/asset verification is not configured | Validate transaction serialization, wallet-account binding, confirmation/error handling and recipient balances on Devnet; add identity verification and escrow only after independent audit and deployment 
| iOS | Deferred by product direction | Keep postponed; do not start an iOS port |
| Physical validation | Not performed | Build and run instrumented tests on at least two real Android phones and a three-node relay topology |
| Android release packaging | API 36 target, AGP 8.9.1 / Kotlin 2.2.21 toolchain, edge-to-edge system-bar/cutout/IME insets, external-keystore release signing gate, and secret-free repository ignore rules are in source | Owner must create/back up the keystore outside the repository, configure release env vars, build/sign locally, verify the AAB certificate, and complete store disclosures |



**Group delivery limitation:** durable frame retirement currently follows a group receipt from a peer; no authenticated group membership roster or per-recipient quorum is available. A receipt therefore proves at least one group member received the frame, not that every group member—including offline members—did.

## 4. Validation policy for this branch

- Pull-request CI runs the focused API 36 instrumentation classes for wallet/receipt encryption, membership pagination, WebView resource policy and router receipts; the full instrumentation suite remains a manual workflow option. The Android workflow produces a debug APK, but neither emulator result substitutes for physical-device interoperability testing or independent security review.
- A successful source edit or commit is not evidence that Android compiles or that a radio/contract works.
- Before production, run local Android build + instrumentation tests, device interoperability tests, protocol review, and independent smart-contract audit as applicable.
- Never fabricate wallet balances, transaction IDs, escrow states, radio metrics, delivery receipts, or test results.


## 5. Mutually signed service-contract envelope (new, not yet integrated)

`MeshServiceContract.kt` defines a bounded version-1 contract envelope for peer-provided services. The canonical UTF-8 signing representation is independent of JSON property order and includes the contract ID, service code, provider and consumer addresses, unit price per MiB, maximum billable payload bytes, maximum cost, validity interval, cancellation policy, sorted relay shares, and both public keys. Provider and consumer each sign the same canonical bytes with P-256 / SHA-256 ECDSA; verification requires both signatures and verifies that each encoded public key hashes to the corresponding party address.

- Billing unit is explicitly `APPLICATION_PAYLOAD_BYTES`. Transport headers, routing metadata and retransmissions are excluded; the receiver's accepted application payload is the accounting boundary. Unit prices are prorated by bytes, with any partial MiB rounded up; the signed maximum cost is a hard cap. `isActiveAt(now)` requires the caller to supply the current clock.
- Relay shares are capped at eight distinct node IDs and a combined maximum of 10,000 basis points.
- The envelope parser is capped at 16 KiB, validates version and field bounds, and rejects non-canonical Base64 encodings and non-P-256 public keys.
- Allowed cancellation policy identifiers are versioned and enumerated; terms cannot be edited without invalidating both signatures.
- Instrumentation coverage is authored in `MeshServiceContractTest.kt` for dual signatures, tampering, envelope round-trip and relay-share bounds.

**Integration boundary:** the contract and `MeshTransferReceipt` are currently standalone protocol primitives. Receipts require both parties to sign each payload batch, carry a monotonically increasing sequence, payload digest, byte delta, cumulative byte count, cumulative capped charge and previous-receipt hash. `verifyNext` checks a single link; callers must validate the entire chain from the genesis hash and persist receipt IDs/hashes idempotently. These types are not yet attached to service offers, file/message transfer, byte counters, wallet ledger, or settlement. They do not prove that the claimed digest corresponds to delivered bytes unless the receiver computes it at the application boundary, and they do not prevent double spending by themselves. Do not treat a signed receipt as a payment or fiat settlement.

The offline wallet store also now refuses new incoming vouchers after its 4,096-entry replay-ID guard reaches capacity rather than evicting old replay IDs and accidentally allowing a previously accepted voucher to be imported again. The human-readable receipt history is separately capped at 1,000 entries; the replay-protection set is never silently pruned. Outgoing voucher history is capped at 4,096 entries. Both incoming acceptance and outgoing-journal insertion now share signature, canonical Base64, address, amount and payload validation; unsigned or modified vouchers cannot be staged for retry. A future export/reconciliation workflow is required before these fail-closed limits can be safely operationalized.


**Local companion binding guard:** the offline portal constructor now rejects wildcard, loopback and public-address binds by default. Loopback binding is available only through an explicit test-only constructor flag; production continues to bind to the device's `100.64.0.0/10` overlay address.


## 6. Durable transfer-receipt journal (new primitive; integration pending)

`MeshTransferReceiptStore` persists signed receipt chains in an app-private `AtomicFile` journal with atomic replacement and fsync-backed completion. Every read and append re-verifies the complete chain from the genesis receipt, including signatures, sequence, byte totals, cumulative capped charge, timestamps and previous-receipt hashes. Exact retries are idempotent; a conflicting receipt at an existing sequence, a gap, a broken chain, malformed storage, malformed per-contract entries or an oversized journal fail closed rather than silently resetting accounting history.

The journal is bounded to 2,048 receipts and 2 MiB. It stores signed accounting metadata only, not payload bytes or private keys. The file is encrypted at rest with AES-GCM using a non-exportable Android Keystore key; a versioned envelope authenticates its header as additional data. Valid legacy plaintext JSON is migrated only after successful parsing and through an atomic replacement. Missing keys, malformed envelopes and failed authentication are fatal and never trigger a silent empty-ledger reset. Android automatic backup is disabled by `android:allowBackup="false"`. The journal is still synchronized only within the app process and uses `AtomicFile` atomic commits; it is not a cross-process transactional ledger, does not coordinate concurrent devices, and does not settle funds. An explicit encrypted export/restore and device-migration recovery workflow remains open. The contract still must be supplied by the caller and verified on each operation.

**Integration remains incomplete:** no service-offer activation path or file/message receiver currently invokes this journal; actual accepted application payload bytes and their SHA-256 digest are not yet measured at the receiver boundary; dual-signature exchange, wallet reconciliation, crash/retry integration and no-double-billing end-to-end tests remain open. The journal must not be presented as proof of payment or fiat settlement.

## 7. Offline wallet record encryption

`MeshOfflineWalletStore` now encrypts its local replay-ID set, incoming receipt history and outgoing signed-voucher journal with AES-GCM. A non-exportable Android Keystore AES key is scoped to the wallet ID. Each preference key is included in GCM additional authenticated data together with a versioned domain and wallet ID, preventing ciphertext from being moved between the replay set, history and outgoing journal.

- Existing legacy `StringSet` replay IDs and plaintext JSON history are migrated on first read using synchronous SharedPreferences commits.
- A missing storage key, malformed/unknown envelope, failed GCM authentication or malformed stored JSON fails closed rather than returning an empty history.
- The signing identity and storage-encryption key are deleted together by the explicit wallet `clear()` operation.
- Instrumentation coverage is authored for ciphertext-at-rest, reopen/read, legacy migration, missing-key and tamper behavior. These tests are not considered passed until the current PR head's targeted CI run finishes.

**Limitations:** this is encrypted local storage for provisional `MESH-LOCAL-UNIT` vouchers. It is not a double-entry/global balance ledger, cannot prevent offline double-spend across disconnected devices, does not create fiat settlement, and has no encrypted export/restore or device-migration recovery workflow. Android automatic backup remains disabled.

## 8. WebView native bridge resource boundary

`MainActivity` exposes `MeshNetNative` through `addJavascriptInterface`, which Android makes visible to every frame. The WebView now serves only the exact HTTPS `appassets.androidplatform.net` origin under `/assets/web/`. Requests for external origins, unexpected paths, user-info URLs, non-default ports and path traversal are answered locally with HTTP 403 rather than falling through to WebView network loading. Top-level external navigation is also rejected. The bundled dashboard Content Security Policy denies frames, network connections, objects and forms.

Instrumentation coverage checks the allowed origin/path and rejects HTTP, foreign hosts, unexpected paths, custom ports and traversal. This narrows the attack surface but does not make an XSS in trusted bundled JavaScript harmless; retain the restrictive CSP, review bundled dependencies and keep native bridge methods narrow. A private authenticated bridge/session protocol and independent security review remain open.

## 9. Membership history paging

`MeshGroupMembershipStore` exposes a rowid high-watermark and bounded cursor pages. A sync request fixes a snapshot upper bound; each page selects rows after the prior cursor and no later than that bound. The peer sends the next cursor only after every event chunk in the page has been queued. The receiver requests the next page, rejects regressions or snapshot changes, and merges signed event IDs idempotently. This removes the prior hard stop at the most recent 64 events for peers using the updated protocol.

**Limitations:** continuation state currently lives in memory and restarts from the beginning after reconnect/process recreation. The protocol is still carried over best-effort v6 ephemeral frames; it is not protocol v7, is not crash-resumable, and does not establish a globally authoritative roster. Snapshot/receipt integration with the message router, durable outbox and UI remains open. Focused instrumentation tests exercise a 70-event sync across multiple pages and fixed-snapshot exclusion of later rows.
