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

**Current state:** selected as the Apache-2.0 replacement candidate; not integrated into the Android runtime. The current app still uses a shared AES-GCM group key and does not provide per-device identity or forward secrecy. No Rust JNI/UniFFI module, native build, key bundle publication, or ratchet-session persistence has been implemented.

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

**Current state:** a first-pass Anchor escrow program source exists under `solana/programs/meshnet-escrow` with order-specific PDA vaults, immutable terms, buyer+provider release, buyer refund after expiry, and configured-arbitrator dispute settlement. Terminal order state is retained for both ordinary and arbitrated settlement; the buyer may explicitly close only a settled order to reclaim its rent. Vault balance and expiry arithmetic have explicit guards. Config initialization now hard-allows only Circle's canonical Devnet USDC mint under the default `devnet` Cargo profile; a mainnet build must explicitly disable defaults and enable the `mainnet` feature, which pins Circle's canonical mainnet USDC mint. Mixed cluster build features are rejected. The Rust build now requires `MESHNET_ESCROW_PROGRAM_ID` and `MESHNET_ESCROW_INITIAL_ADMIN` to be supplied as real public keys; no placeholder program/admin defaults remain. These are build-time identity inputs, not proof of key ownership or deployment. This source is not built, tested, deployed, audited, or integrated into the Android app. No funds can be accepted or transferred by MeshNet.

## 3. Other outstanding work

| Area | Current state | Safe next step |
|---|---|---|
| File transfer | 10 MiB capped streaming sender, 16-chunk bounded windows, durable receiver checkpoints, streaming SHA-256 verification, terminal ACK only after verified finalization, cancellable sender workers, 24-hour cancellation tombstones, transfer-ID rotation after cancel, user-mediated export, 50-file / 100 MiB inbox quota, and acknowledged-byte / average-throughput progress are implemented in source | Validate cancellation/replay ordering, crash recovery, quota eviction, save/export behavior, throughput accuracy, and large-file interoperability on devices |
| Voice | User-controlled AAC voice notes and opt-in live group voice over encrypted v4 EPHEMERAL AMR-NB packets, per-origin jitter buffers/decoders, 20 ms frames, and bounded mixing are implemented in source | Validate codec behavior, acoustic echo, loss concealment, microphone foreground-service policy, latency, and battery use on devices |
| Wi-Fi Aware / Wi-Fi Direct | Optional Wi-Fi Aware discovery and PSK-negotiated TCP transport source is implemented alongside Wi-Fi Direct; device interoperability remains unverified | Validate Aware availability changes, multi-peer paths, coexistence with Wi-Fi Direct, and real-device interoperability |
| Congestion/routing | Per-peer BLE queue backoff/retry, ingress-excluding relay dispatch, and hop-first/arrival-age duplicate-route scoring are implemented in source; no destination-aware next-hop or radio signal-quality selection exists | Add route advertisements, measured link metrics, expiry-aware scheduling, bounded route expiry, and loop-safe unicast forwarding |
| Solana escrow | Anchor source includes terminal-state retention, buyer-only close-after-settlement, vault checks, and checked expiry arithmetic; not compiled, tested, deployed, or audited | Set real program/admin IDs after ownership confirmation, add adversarial/integration tests, reproducible build, external audit, deployment gates, and full transaction UX |
| Wallet | Android uses MWA 2.0.7 for user-approved Devnet authorization/deauthorization; public key and auth token are stored encrypted with a non-exportable Android Keystore key and restored after process recreation. No transaction is constructed, signed, or submitted; dApp identity URI/asset verification is not configured | Add identity verification and a transaction builder only after the escrow program is deployed, independently audited, and verified on the target cluster 
| iOS | Deferred by product direction | Keep postponed; do not start an iOS port |
| Physical validation | Not performed | Build and run instrumented tests on at least two real Android phones and a three-node relay topology |
| Android release packaging | API 36 target, AGP 8.9.1 / Kotlin 2.2.21 toolchain, edge-to-edge system-bar/cutout/IME insets, external-keystore release signing gate, and secret-free repository ignore rules are in source | Owner must create/back up the keystore outside the repository, configure release env vars, build/sign locally, verify the AAB certificate, and complete store disclosures |



**Group delivery limitation:** durable frame retirement currently follows a group receipt from a peer; no authenticated group membership roster or per-recipient quorum is available. A receipt therefore proves at least one group member received the frame, not that every group member—including offline members—did.

## 4. Validation policy for this branch

- Do not run GitHub Actions / CI unless explicitly requested.
- A successful source edit or commit is not evidence that Android compiles or that a radio/contract works.
- Before production, run local Android build + instrumentation tests, device interoperability tests, protocol review, and independent smart-contract audit as applicable.
- Never fabricate wallet balances, transaction IDs, escrow states, radio metrics, delivery receipts, or test results.
