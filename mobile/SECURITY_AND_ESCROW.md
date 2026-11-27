# Security, cryptography, and escrow implementation decisions

This document records an implementation decision, not a claim that the integrations below are already active.

## 1. Cryptography: use vodozemac instead of libsignal

**Selected candidate:** [matrix-org/vodozemac](https://github.com/matrix-org/vodozemac), Apache-2.0, Rust crate. It implements Olm (Double Ratchet) and Megolm, and its maintainers publish a security audit by Least Authority. The current crate line is 0.11.x; pin an exact reviewed version and commit the dependency lockfile when integrating.

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
6. Only then migrate messages. Keep protocol v3 group AES-GCM as a clearly marked legacy mode until all devices have upgraded; do not silently reinterpret old ciphertext as ratchet ciphertext.

**Current state:** selected as the Apache-2.0 replacement candidate; not integrated into the Android runtime. The current app still uses a shared AES-GCM group key and does not provide per-device identity or forward secrecy.

## 2. Escrow: non-custodial Solana PDA escrow; no custodial server wallet

**Selected starting point:** [Solana Foundation escrow program](https://github.com/solana-foundation/escrow), MIT-licensed, with an Accretion audit report and an explicit audit-status file. Its published deployment is Devnet only. It is a receipt-based deposit/withdrawal program; it is not, by itself, a marketplace contract that releases payment to a service provider after delivery or resolves buyer/provider disputes.

For MeshNet bandwidth or service payments, the target design is:
- USDC SPL token only, with the canonical mint explicitly pinned per cluster.
- One isolated PDA vault and immutable terms per order/session; never a shared hot wallet.
- Explicit payer, provider, amount, expiry, fee cap, and dispute authority in the order state.
- A two-party signed completion or a clearly identified arbiter decision before release; payer-only refund after expiry when no valid completion exists.
- Checked integer arithmetic, token-program and mint validation, associated-token-account validation, replay protection, pause/emergency procedures, and no arbitrary CPI hooks.
- Fee collection separated from user escrow principal; multisig + timelock for any administrative authority.
- Client verifies the program ID, cluster genesis/network, mint, PDA derivation, transaction simulation, and finalised transaction signature before showing a settled status.

The Foundation's DvP program is another audited option for atomic exchange of two token/asset legs with a named settlement authority. It is appropriate for swaps, not a substitute for a reviewed delivery/dispute state machine for bandwidth metering.

### Safe activation gates

Do not accept or display real deposits until all of these are available and reviewed:
1. A deployed program ID and reproducible build hash for the exact audited commit on the intended cluster.
2. The full audit report and a documented diff showing that deployed code matches the audited scope; no unreviewed code delta.
3. Canonical USDC mint, token program, RPC/cluster identity, and upgrade-authority policy pinned in release configuration.
4. A multisig/timelock or immutable program policy, emergency pause/recovery plan, and documented dispute authority.
5. Adversarial program tests for unauthorized release/refund, replay, wrong mint, wrong vault, arithmetic overflow, expiry boundaries, account substitution, and frozen-token behavior.
6. External security review and explicit product/legal approval for fees, custody characterization, disputes, and supported jurisdictions.

**Current state:** no escrow program is deployed or integrated in MeshNet; no wallet is connected; no funds can be accepted or transferred by the app. The official template's Devnet deployment must not be presented as production-ready or assumed to support mainnet. No private keys, RPC credentials, or deployment authority are available in this repository context, so deployment or real-money activation is intentionally not attempted.

## 3. Other outstanding work

| Area | Current state | Safe next step |
|---|---|---|
| Large files | Not implemented; current frame cap is not a file protocol | Add a separate authenticated chunk manifest, bounded chunk window, resume bitmap, hash verification, cancellation, and quota controls |
| Voice | Not implemented | Add explicit user-controlled recording, encrypted chunk streaming, consent and lifecycle controls |
| Wi-Fi Aware / Wi-Fi Direct | Not implemented | Add transport abstraction and capability negotiation, then implement one transport at a time |
| Congestion/adaptive routing | Only bounded BLE queues and TTL relay exist | Add per-peer metrics, backpressure, expiry-aware scheduling, and loop-safe route scoring |
| iOS | Deferred by product direction | Keep postponed; do not start an iOS port |
| Physical validation | Not performed | Build and run instrumented tests on at least two real Android phones and a three-node relay topology |

## 4. Validation policy for this branch

- Do not run GitHub Actions / CI unless explicitly requested.
- A successful source edit or commit is not evidence that Android compiles or that a radio/contract works.
- Before production, run local Android build + instrumentation tests, device interoperability tests, protocol review, and independent smart-contract audit as applicable.
- Never fabricate wallet balances, transaction IDs, escrow states, radio metrics, delivery receipts, or test results.
