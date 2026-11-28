# ESI Assistant Memory — MeshNet

_Last updated: 2026-11-28 (repository engineering mission)_

## Project intent
MeshNet is intended to be a fully offline-capable, decentralized, peer-to-peer mesh communication and data-sharing application. Direct device-to-device communication is the priority; local Wi-Fi and multi-hop relaying are alternatives. Internet is optional and user-controlled, never required for core operation.

## Requirements decisions confirmed by the user
- Automatic nearby device discovery and network formation.
- Public shared space, public channels, private groups, and private one-to-one conversations.
- Hybrid identity: cryptographic device identity plus user profile; secure identity portability.
- Direct delivery plus temporary encrypted store-and-forward for offline recipients.
- Nearby communication with multi-hop relaying and automatic route recovery.
- Maximum practical security: end-to-end encryption, device authentication, key rotation, anti-spoofing/replay protections, permissions, blocking/reporting.
- Full multimedia: text, images, video, files, voice/video calls, live audio/video and local file sharing.
- Open public network; discovery does not grant trust or private access.
- No central network administrator or mandatory server.
- Use available transports as supported by device/OS: Bluetooth/BLE, Wi-Fi Direct, Wi-Fi Aware and local Wi-Fi.
- Keep network services active in background as far as Android/OEM limits permit; disclose foreground-service and battery constraints.
- Optional internet path, controlled by the user.
- Users may offer internet sharing at a custom data-based price; peers can accept/reject offers and the system may select the lowest-cost acceptable route.
- Services may be free or paid; all services are accounted by data volume.
- Wallet supports internal MeshNet credit and real money, while offline fiat settlement is not represented as final until a trusted online payment confirmation.
- Pricing supports fixed tariffs and negotiated offers. A device has one public rate, equal for all receivers. Active contract prices cannot be unilaterally changed.
- Traffic accounting includes protocol overhead and retransmissions only as explicitly defined by the contract; avoid charging unrelated traffic.
- Multi-hop relays share revenue proportional to forwarded traffic. System proposes a split; provider may adjust before contract; every relay must consent; changes after start require a new mutual agreement.
- Contracts are digitally signed by both parties and include service, data-volume unit, price, caps, validity, cancellation and settlement rules, unique ID and verifiable receipts.
- Route failure triggers automatic recovery; if no viable route exists, pause/stop and charge only actual billable traffic under contract; resume from last confirmed chunk and prevent duplicate charging.
- Financial controls include wallet, balances, transactions, debt, income, settlement, reports, caps, alerts, auto-stop, budgets and economic route selection.
- Disputes pause disputed transfer/payment, preserve evidence and use peer arbitration by randomly selected eligible devices; replace unavailable arbitrators automatically, and do not finalize without quorum.
- Hybrid financial record storage: primary copies on both parties' devices; optional encrypted backups on trusted peers; host cannot read private content; owner controls expiry.
- Storage sharing uses manually selected capacity and owner-controlled quota/rate/stop.
- Files can be public or private; private files are encrypted and permission-controlled.
- Search spans public, group and nearby/local content; private metadata must not leak. Hybrid search starts locally then propagates with TTL, time, node and request-size limits; results stream incrementally.
- Public channels, private groups and common public space are supported.
- Only users meeting validity/reputation criteria can create public channels; all users may consume/join them.
- Validity is a combination of activity history, social trust endorsements and good behavior.
- Initially, each valid user may create only one public channel; quota can increase with reputation.
- If a channel creator becomes inactive, eligible active members elect a replacement manager.
- Only valid and active channel members may vote in manager elections.
- Manager removal for misconduct uses a formal complaint, evidence review and vote by valid active members.
- For serious allegations, suspend all managerial privileges temporarily while review proceeds; the person may continue participating as an ordinary member and retain the right to respond.
- User asked that all remaining design decisions be selected using best judgment rather than asking one question at a time; proposed decisions should be clearly identified as proposals, not falsely represented as implemented.

## Product and delivery principles
- Android is the primary offline mesh runtime. Web should be an optional local companion/dashboard and must not introduce cloud dependency into core operation.
- Keep architecture modular, protocol shared where practical, and maintain organized branches/PRs with tests and documentation.
- Deliver a verifiable trial build before broader completion work when requested.
- Never claim features are implemented or CI is green without checking the repository and latest workflows.
- Do not merge PRs or make irreversible project changes without explicit authorization. For the mission explicitly authorized on 2026-11-28, existing PRs may be completed and merged only after the actual required GitHub checks pass, conflicts are resolved, the destination branch is current, and repository security/branch-protection rules are respected. This authorization does not permit bypassing protections, force pushes, or suppressing required checks.
- Before implementation, compare this requirements record with actual repository state and split work into coherent GitHub issues/PRs.

## Current repository baseline and PR disposition (2026-11-28)
- Canonical repository: https://github.com/esi2ei/meshnet (owner `esi2ei`, repository `meshnet`); default branch is `main`.
- Dedicated memory branch: `assistant-memory`. The memory file had been removed at its HEAD by commit `280f98b9f27238049b430a336f9ab50f908d2edc` ("revert: remove assistant memory file per user request"). The user subsequently explicitly requested this file be read and updated, so this version is restored on the dedicated memory branch only.
- Open PR inventory found on 2026-11-28: PR #2 and PR #29.
- PR #2, "Defer Mobile Wallet Adapter 2.2.1 until Android API 37 support": Draft / deferred. Its only code change is the MWA client library version bump to 2.2.1 in `mobile/app/build.gradle.kts`. Prior GitHub Actions evidence documents failure at Android `:app:checkDebugAarMetadata` because the selected MWA/AndroidX dependency set requires compileSdk 37 and a newer AGP; it is not safe to merge independently. Current PR head at initial review: `b5c59cceab6c5f22a6cab23af1a3a2290378df9c`. Revisit only as part of a coordinated, exact-head-tested Android toolchain update.
- PR #29, "MeshNet: default-deny unverified delivery receipts": Draft; branch `work/offline-mesh-completion`, base `main`. Current head at initial review: `0b7a301a0b9f37697c54b8ef934ceb778d2c77fc`. It contains security, persistence, protocol, routing, Android tests and documentation changes. Its description explicitly leaves significant acceptance work open: physical multi-device BLE/Wi-Fi/OEM validation; per-device private-session E2EE, ratchets, forward secrecy and identity enrollment/revocation; full group-delivery receipt/router/outbox/UI semantics and crash-resumable membership sync; private-file ACL/search/backup/export; and real billing, settlement, governance, reputation, marketplace and arbitration functions. It also states the full Android instrumentation suite is manual-only. Do not merge while product/security acceptance gates remain unresolved or exact-head required CI is not verified.
- Existing issue references include #17–#28 and #31–#32 as described by PR #29; issue and roadmap status must be checked against GitHub before claiming completion.
- Physical-device or lab-only tests must be documented as Pending / Not Run with prerequisites and manual steps. Never report them as passed. Such tests alone should not block merge unless they are explicit non-waivable security, legal or acceptance gates. Failed GitHub automation, unresolved security defects, merge conflicts and branch-protection requirements remain real blockers.
- No PR is considered merged by this memory record. Verify live GitHub state before each action or report.

## Working style
- User prefers Persian.
- During requirements discovery, ask one question at a time unless the user asks for all remaining questions together.
- User previously asked to transfer accumulated decisions and remaining proposed requirements into GitHub issues; keep that traceability in view and verify the current issue set.


## Follow-up verification — 2026-11-28
- PR #29 exact head `0b7a301a0b9f37697c54b8ef934ceb778d2c77fc` completed GitHub Actions run #231 successfully. Node 22 bridge-contract validation, TypeScript typecheck, Vite production build, Android debug APK assembly, APK signature/checksum verification, and focused API 36 instrumentation all passed. Instrumentation executed 195/195 tests, 0 failed and 0 skipped. Full Android instrumentation was skipped because it is a manual-only workflow input. No physical-device/radio/OEM/VPN hardware validation is claimed.
- Successful run #231 retained only the web dashboard artifact (335,831 bytes, one-day retention); no APK or test report artifact was uploaded on success. No before/after artifact baseline was available, so no quantitative saving is claimed.
- Added PR conversation comments #7238766596 to PR #29 with exact-head CI and Pending / Not Run physical-test status, and #7238766924 to PR #2 documenting current-head Android AAR failure and 142-commit divergence from main.
- Both PRs remain open Draft. PR #2 is deferred due actual Android API 37 / AGP compatibility failure and stale branch; PR #29 remains unmerged because product/security acceptance gates remain unresolved despite green configured PR CI.
- Current main remains reported unprotected, no active repo rulesets were returned, and detailed branch-protection read remains blocked to this integration. Owner/admin must configure and verify a `main` ruleset requiring CI checks and restricting force pushes/deletion. Do not merge or push directly to main as a workaround.
