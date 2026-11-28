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


## Repository mission results — 2026-11-28 (continued)

### Merged PR #34 — VPN packet-drop visibility
- PR #34, "Make VPN overlay packet drops visible", merged to main as `48a2a5f578bffb0e2a6cf82532f1052bc70c5d9d`.
- Exact-head run #232 on `30ce0af4a65fb117ee034125580264bb0988a1bf`: Node22 TypeScript/web build passed; Android debug APK build and signature/checksum verification passed; focused API36 instrumentation **169 tests, 0 failed, 0 skipped**, Gradle BUILD SUCCESSFUL.
- The test and merge commits have the same Git tree SHA `8e6c7767337ac6f76994f92223fad60a9a58f15c`.
- It adds a session-local cumulative counter for VPN TUN packets that `sendIpPacket()` does not fully accept, reporting immediately and then at most every five seconds. It is observability only: no durable outbound queue, socket backpressure, fragment retry or retransmission. Issue #32 remains open. Physical route-loss recovery remains Pending / Not Run.

### Merged PR #35 — GitHub Actions SHA pinning
- PR #35, "Pin GitHub Actions to immutable commit SHAs", merged to main as `0fe421254c8b8e60ec0b03f064d5309bea278079`.
- The branch was synchronized after PR #34 with a non-force two-parent merge commit `684cded88f24de9c24f704e08faec8545a33a435`; parents were prior PR head `0837d019addf427f7f2acc88313b6a6f3d9e81af` and main `48a2a5f578bffb0e2a6cf82532f1052bc70c5d9d`.
- Exact synchronized-head run #234 on `684cded88f24de9c24f704e08faec8545a33a435`: Node22 typecheck/build passed; Android debug APK/signature/checksum passed; focused API36 instrumentation **169 tests, 0 failed, 0 skipped**, Gradle BUILD SUCCESSFUL.
- All 12 `uses:` occurrences now reference exact upstream 40-character SHAs. GitHub verified the pinned commits for actions/* and gradle/actions; the pinned commits for android-actions/setup-android and reactivecircus/android-emulator-runner are not marked verified. SHA pinning prevents tag drift, but does not establish signature provenance for those two.
- Successful run #234 retained only web dashboard artifact (284,277 bytes, one-day retention); no APK or test-report artifact on successful PR. Full instrumentation skipped as manual-only.

### Documentation PR #36 — current state
- Created open PR #36, "Restore feature preservation contract and refresh roadmap", branch `docs/refresh-roadmap-and-preservation-contract`, head `8a39982dab8cf46166e181bd76b74fd49bd397eb`, base main `0fe421254c8b8e60ec0b03f064d5309bea278079`.
- Adds missing `docs/FEATURE_PRESERVATION_PLAN.md` (copied from the reviewed version on PR29 branch) to repair existing README/ARCHITECTURE links.
- Updates roadmap baseline and actual CI policy; clearly labels old "CI not requested" notes as historical; records PR #2/#29/#34/#35 exact outcomes, physical test Pending/Not Run, unprotected main, npm lockfile gap, post-merge trigger behavior and security limitations.
- Documentation-only: two files changed, 227 additions, 4 deletions. PR is open, non-draft and mergeable at last check. No GitHub workflow run was visible yet for exact head; do not claim CI passed or merge until a run is triggered and verified.

### Current repository delivery state
- Main HEAD after PR #35: `0fe421254c8b8e60ec0b03f064d5309bea278079`.
- Open PRs at last full listing: #2, #29, #36. #34 and #35 are merged.
- PR #2 remains Draft/deferred due failed compileSdk37 / AGP9.1 dependency metadata checks and a stale branch (142 commits behind at review).
- PR #29 remains Draft despite its run #231 success (195 tests) because device E2EE/key lifecycle, durable protocol-v7 group delivery, crash-recoverable membership, private file controls and independent security acceptance are incomplete.
- PR #36 is docs-only and still requires exact-head GitHub CI before merge.
- Current main reports `protected:false`; no rulesets were returned. The GitHub connection cannot administer/read detailed branch protection; owner/admin action is required. No protection bypass or force push was used.
- The workflow has `pull_request` and manual `workflow_dispatch` triggers but no `push: branches: [main]`, so standalone post-merge CI does not automatically launch. The tested PR34 tree was byte-identical to the merged tree.
- No npm lockfile exists; CI uses `npm install`, with cache keyed by `package.json`. Deterministic npm ci remains open.
- Dependabot/Code Scanning/Secret Scanning alerts could not be retrieved using the connected GitHub integration; do not claim the alert queues are clean.
- Full Android instrumentation was Not Run in runs #231–#234; physical BLE/Wi-Fi/OEM/background/battery/radio testing remains Pending / Not Run.
- Run artifact observations (web dashboard, one-day retention): #231 335,831 bytes; #232 284,277; #233 283,172; #234 284,277. No comparable before/after baseline exists; do not claim quantitative CI or artifact savings.
- `SECURITY.md` explicitly says this repository is a prototype and not suitable for confidential, safety-critical or financial traffic. Production release is not ready: no per-device ratchet/forward secrecy, audited crypto, real-money escrow settlement, release keystore/AAB, physical-device evidence or legal/store approvals.


## Latest exact-head work — 2026-11-28

- PR #29 branch was synchronized with latest main using two non-force commits: Actions pinning commit `d575685721d43480cd57e2c0494385cf0af6646d`, then two-parent merge commit `c41e48dfbc89c63619776baf1ba6c4503bee7259`. The merge preserved its native bridge contract guard and MeshRuntimeStatusSerializerTest, merged PR #34's VPN source/test/QA changes, and retained all 12 SHA-pinned Action references from PR #35. The test file and VPN reporter are verified in the branch. It now compares 130 commits ahead / 0 behind current main and remains Draft. Intermediate run #236 was cancelled by the subsequent branch synchronization and is not a failure result. Current authoritative run #237 is active on exact head `c41e48dfbc89c63619776baf1ba6c4503bee7259`; Node22 typecheck/web build passed, Android job still running at last check. No final CI result is claimed yet.
- PR #2 after main advances is 144 commits behind / 2 ahead, diverged, and GitHub reports non-mergeable; its existing AAR metadata failure remains unresolved.
- PR #36 exact head `8a39982dab8cf46166e181bd76b74fd49bd397eb`, base `0fe421254c8b8e60ec0b03f064d5309bea278079`, docs-only, non-draft, mergeable. Run #235 is active: Node22 typecheck/web build passed, Android APK and focused emulator test job still running at last check. Do not merge until run #235 passes on this exact head.
- Latest GitHub-visible open PRs are #2, #29 and #36. PR #34 and #35 are merged; main HEAD is `0fe421254c8b8e60ec0b03f064d5309bea278079`.
- Issue #31 comments include merged action pin result, unresolved main protection/npm lockfile/security-alert access, and PR #36 roadmap repair. Issue #32 continues to track full VPN reliability beyond diagnostics. PR #2/#29 have been given fresh synchronization status comments.


## Mission revalidation — 2026-11-28 (new execution)
- User re-authorized end-to-end completion of existing open PRs, with strict conditions: do not bypass branch protection, do not force-push, do not suppress required checks, and merge only after actual required GitHub checks pass and the target branch is current.
- Before work, verified the canonical repository is `esi2ei/meshnet` (repository ID `1268313307`), public, default branch `main`; connected GitHub identity has admin/maintain/push permissions. The dedicated `assistant-memory` branch exists, and this file was read from it.
- Revalidation of all live PRs, workflow runs, issues/roadmap and current main is in progress. The prior status entries above are historical checkpoints, not assumptions about the current live state.
- No new merge, force-push, branch-protection bypass or direct push to `main` is authorized or performed by this entry.


## MeshNet mission execution checkpoint — 2026-11-28
- Live open PR inventory verified: #2, #29, #36. Main at audit baseline: `0fe421254c8b8e60ec0b03f064d5309bea278079`.
- PR #2: kept Draft/deferred. Exact GitHub run #53 failed `:app:checkDebugAarMetadata` because Mobile Wallet Adapter 2.2.1 requires compileSdk 37 while current Android build uses compileSdk 36 / AGP 8.9.1. Compared with current main, branch is 2 commits ahead and 144 behind. Comment recorded on PR.
- PR #29: kept Draft. Exact-head run #237 at `c41e48dfbc89c63619776baf1ba6c4503bee7259` passed Node 22 bridge contract/typecheck/web build, Android debug APK assembly/signature verification, and 197 focused API 36 emulator tests. Full Android suite was skipped/not run; physical radio/device tests Pending / Not Run. Static review confirmed unresolved P0 group-broadcast outbox: without immutable recipient snapshots/per-member receipts broadcast messages cannot retire, 6,000-row queue backpressures new messages. Other P0/P1 security, recovery, ACL and protocol integration gaps remain. Comments recorded on PR #29 and issue #32.
- PR #36: documentation-only, 3 files (roadmap, feature-preservation contract, release test plan). Fixed two P2 review inaccuracies, resolved both review threads, added a reproducible 9-case physical-device release QA plan plus CI-01 full Android suite procedure and SEC/REL/PAY release gates; all physical/manual/external tests explicitly Pending / Not Run. Roadmap links the test plan and corrects PR status/branch divergence/CI details. Issue #28 notified.
- PR #36 exact current head is `06a8611a3dc9b72ce169c5ce73591e9ef1030d96`; base `main` is `0fe421254c8b8e60ec0b03f064d5309bea278079`; mergeable=true, not draft. Workflow run #242 (`46144449702`) Node 22 job succeeded; Android APK assembly, signature/checksum verification and KVM setup passed; focused API 36 instrumentation is still in progress at this checkpoint. Do not merge until this exact-head run fully concludes successfully and the head/base are revalidated.
- Repository governance finding: main is reported unprotected, protection.enabled=false, and rulesets API returned []; detailed branch-protection endpoint was 403. GitHub connector has no admin/ruleset mutation or workflow_dispatch operation. Do not claim protection is configured or full suite was run.
- CI controls observed: one MeshNet workflow, Node 22 only, shared web dist artifact, concurrency cancellation; successful PR retains minimal web dist for 1 day, failure/manual diagnostics 3 days, conditional APK 7 days. No baseline permits quantifying savings. No npm lockfile; CI uses npm install and cache key package.json; reproducible npm ci is still open.
- Security observations: SECURITY.md states prototype, not for confidential/safety-critical/financial traffic; shared group AES-GCM is not per-device E2EE/forward secrecy; wallet is Devnet prototype, no production settlement. Alert APIs unavailable through integration; do not say security alerts are clear.


## Session closeout status — 2026-11-28
- Rechecked PR #36 exact-head workflow run #242 (ID `46144449702`, head `06a8611a3dc9b72ce169c5ce73591e9ef1030d96`). Node 22 typecheck/web production build passed; Android debug APK build and signature/checksum verification passed; focused API 36 test step remained `in_progress` in the last live API read. The full Android suite remained `pending/skipped` by workflow design (manual dispatch only).
- PR #36 remains open and mergeable, not merged. No merge was attempted because the exact-head workflow had not returned a terminal successful result. Main remained at `0fe421254c8b8e60ec0b03f064d5309bea278079` and unprotected at last check.
- No physical-device tests, full Android instrumentation suite, independent cryptographic review, production-signed AAB or legal/payment review were run. All are Pending / Not Run as appropriate.
- Do not represent the repository as product-complete: PR #2 deferred after a real dependency/compileSdk CI failure; PR #29 remains Draft due unresolved P0 group broadcast durable-outbox behavior and other P0/P1 security/product acceptance gaps; PR #36 docs changes are complete in its branch but awaiting exact-head test completion before merge.


## Live mission start — 2026-11-28
- Verified canonical repository via GitHub API: `esi2ei/meshnet`, repository ID `1268313307`, public, default branch `main`; connected identity reports admin/maintain/push permissions.
- Verified dedicated branch `assistant-memory` at commit `27ee3f28cbadda5fe9b2cc0dd17aed50f7f09078` and read this complete memory file before any project work.
- The current user has explicitly authorized completion/merge of existing PRs only when current-head required GitHub checks pass, conflicts are resolved, and branch-protection/security rules are honored. No force push, protection bypass or direct-to-main workaround is permitted.
- New live revalidation session started. Existing PR/workflow details elsewhere in this file are historical checkpoints and must be rechecked before action. No PR mutation or merge has been performed in this session as of this entry.


## Live mission results — 2026-11-28 (GitHub session)

### Repository and memory
- Reconfirmed canonical repository `esi2ei/meshnet` (repo ID `1268313307`), public, default branch `main`; connection reports admin/maintain/push access.
- Read this memory file first in this session. Initial session note was committed to `assistant-memory` as `70ef69195e0af94b566aaa0545e8e0a34285cb4b` before project changes.

### PR #36 completed
- PR #36 “Restore feature preservation contract and refresh roadmap” passed exact-head CI run #242 (`46144449702`) on `06a8611a3dc9b72ce169c5ce73591e9ef1030d96`: Node 22 typecheck/build and Android debug build/signature plus focused API 36 emulator tests all successful; full Android instrumentation suite was skipped because manual-only.
- Pre-merge: 8 commits ahead / 0 behind current base, mergeable clean, both review threads resolved/outdated, both GitHub check-runs success.
- Squash-merged successfully. Merge commit: `282801743ffc3fdc1dc2d02439fad63c025c9c2a`. PR closed with merged=true. Post-merge main tree `c475f45bfaa1fce250f236045a32ed4c4baf6593` exactly matched the tested PR head tree. No separate push-triggered post-merge run exists because workflow has no push trigger.

### Existing PR dispositions
- PR #2 remains open Draft/deferred. Exact-head run #53 (`46065695660`) failed Android `:app:checkDebugAarMetadata`: MWA 2.2.1 and AndroidX Core 1.19.1 need compileSdk 37; AndroidX Core 1.19.1 also needs AGP 9.1.0+, while the PR branch uses compileSdk 36 / AGP 8.9.1. Node 22 and Node 24 web jobs passed; Android job failed. Branch is 2 ahead / 145 behind current main and diverged/non-mergeable. Added explanatory comment ID `7239354053`. Do not merge independently; revisit in a coordinated API 37 / AGP 9.1+ toolchain migration.
- PR #29 remains Draft and not merge-ready. Exact-head run #237 (`46143220576`) passed Node 22 bridge/typecheck/build, Android debug APK/signature, and focused API 36 instrumentation (197 tests recorded in prior PR discussion); full suite manual-only and physical-device tests not run. After PR #36, branch is 130 ahead / 1 behind main and diverged. Review of code/issue #32 confirms unresolved P0 group delivery lifecycle (no immutable recipient snapshot + per-member transport integration; broadcast ACKs fail closed and outbox can exhaust at 6,000), shared-key AES-GCM/no per-device ratchet or forward secrecy/key rotation, and other P1 gates (crash-resumable roster reconciliation, private-file ACL/revocation, IP packet retry/backpressure, hardware validation). Added clarifying PR comment ID `7239328703`. Run #237 does not validate the now-current main base and does not justify merging PR #29.

### Isolated data-preservation work
- Extracted the self-contained outbox database migration fix from Draft PR #29 onto fresh branch `fix/preserve-legacy-outbox-migrations`, based on main `282801743ffc3fdc1dc2d02439fad63c025c9c2a`.
- Commits: `13c235856c1dbf6070880bf551c242eb71e77c42` (quarantine migration) and `2f71399d5b17fe407268c6c7ec92f8912b295af9` (schema migration tests).
- Created Draft PR #37: https://github.com/esi2ei/meshnet/pull/37. It changes only `MeshQueueStore.kt` and `MeshQueueStoreTest.kt`. It archives incompatible pre-v6 outbox frame bytes/metadata and replay markers into queryable quarantine tables within SQLite migration, keeping them out of automatic dispatch/replay. Test fixtures cover schema versions 1–7 and v9→v10 preservation.
- Linked PR #37 to issue #32 in comment ID `7239328355`; stated that UI recovery/export and interrupted-upgrade stress validation still remain, so issue #32 is not closed. PR #29 has been informed that this slice was extracted.
- Exact-head CI run #243 (`46145317215`) on `2f71399d5b17fe407268c6c7ec92f8912b295af9`: at the last live check, Node 22 install/typecheck/build and web artifact succeeded; Android SDK/JDK/Gradle setup, debug APK assembly and APK signature/checksum verification succeeded; focused Android API 36 instrumentation step was still in progress. Run not yet concluded; PR #37 remains Draft and has NOT been merged. Do not report test success until final run/checks are green and exact head/base are rechecked.

### Current main audit and remaining gates
- Current main: `282801743ffc3fdc1dc2d02439fad63c025c9c2a`.
- Main CI: single workflow, Node 22, shared web dist artifact between web and Android jobs, concurrency cancellation, 1-day web artifact retention, diagnostics only on failure/manual (3 days), debug APK only manual/trial branch (7 days), 40-char action SHA pins, workflow-level contents:read. PR CI runs focused API36 emulator instrumentation; full suite manual-only. No push-to-main trigger.
- CI determinism gap: no npm lockfile and workflow uses `npm install`, cache keyed on package.json. Do not fabricate a lockfile; local environment has no network/cache to resolve transitive packages. Issue #31 remains.
- Stale docs found in `mobile/README.md`: says protocol v5, describes 24-hour outbox retention/oldest-frame eviction, says broadcast receipts from any group member are accepted, and says workflow is manual-only. Current main is protocol v6; `MeshQueueStore.enqueue` rejects new frames at 6,000 without evicting existing queued messages; `receiptMatches` fails closed for group broadcasts and requires destination match for directed receipts; CI runs on PRs. Also `mobile/README.md` and `CONTRIBUTING.md` refer to AGP/Kotlin/Gradle versions older than main (AGP 8.13.2, Kotlin 2.4.21, Gradle 8.13), and roadmap baseline still says 0fe421 despite PR #36 merge. A separate documentation correction was identified but not yet committed.
- `SECURITY.md` states prototype must not be relied on for confidential/safety-critical/financial traffic. Shared group AES-GCM still lacks per-device sessions, ratchets, forward secrecy, secure rotation/revocation and independent crypto audit. No production release/AAB or production signing key confirmed. GitHub releases endpoint returned empty. Repository tree contains no LICENSE file; license choice requires owner decision.
- Release test plan confirms physical HW-01 through HW-09 Pending/Not Run: two-device BLE/GATT, Wi-Fi Direct/Aware, three-device relay, VPN/TUN/DNS, OEM lifecycle/background, file resume, audio lifecycle and performance/battery. Independent cryptographic review, production signing/AAB/store/legal checks Pending/Not Run. Emulator CI is not physical-device evidence.
- Branch protection: repository rulesets endpoint returned empty; branches API marked main unprotected. GET branch protection returned 403 to connected integration. No permission to configure protection was available. Dependabot/Code Scanning/Secret Scanning alert queues could not be read through the integration; do not claim them clear.
- Workflow and security posture: only one MeshNet workflow, pinned actions, contents:read, no pull_request_target; no CI lockfile; no release workflow. No quantitative before/after CI or artifact saving baseline; do not claim measured savings.
- Main open PRs at this checkpoint: #2, #29, #37. #34 and #35 already merged. No other open PRs were present in the live list.


## Live repository audit — 2026-11-28 (new session)
- At session start, verified the canonical repository as `esi2ei/meshnet`, repository ID `1268313307`, owner `esi2ei`, public, not archived, default branch `main`. Repository URL is exactly https://github.com/esi2ei/meshnet.
- Read this memory file from the dedicated `assistant-memory` branch before repository work. Current live state, PR inventory, CI runs, and app source-distribution findings must be revalidated in this session; preceding checkpoints are historical only.
- User requested a current repository/PR audit, safe merges only after validation, repair of merge blockers, and investigation/fix of why application source code is visible in the app. No project changes or merges have been made in this session as of this entry.
- Safety constraints retained: never force-push, bypass branch protection, suppress required checks, or merge a Draft/deferred PR without independently verifying exact-head CI and product/security acceptance. Report checks that are unavailable or not run as such.


## MeshNet source-visibility and PR audit — 2026-11-28 (session closeout checkpoint)
- Confirmed canonical repo `esi2ei/meshnet`, repo ID `1268313307`, owner `esi2ei`, public, default branch `main`. At audit baseline main was `e269bc0b3e2c231b027782ffd9657505e9c4aa50`. Main and all returned branches are marked unprotected; no protection bypass was used.
- PR #37, “Preserve incompatible legacy outbox records during protocol upgrades”, was already merged into main at `e269bc0b3e2c231b027782ffd9657505e9c4aa50`. Run #244 (`46156597198`) passed Node and Android jobs; main tree SHA `c7aeca25be3ca857b77c340329f011133eaa9be3` matches PR #37 head tree.
- Reconfirmed PR #29 is still Draft and unsafe to merge: it compares 130 commits ahead / 2 behind main and contains known P0/P1 group delivery/outbox, per-device E2EE/key lifecycle, membership/file-recovery and independent security acceptance gaps. Its old successful run #237 is not current-main validation.
- Reconfirmed PR #2 is still Draft/deferred: Mobile Wallet Adapter 2.2.1 requires Android compileSdk 37 and AGP 9.1+, while the validated project toolchain remains on API 36. Do not merge independently.
- Root cause of source visibility: `src/App.tsx` exposed an “Application source code” section backed by `src/components/CodeExporter.tsx`; CodeExporter used repository-wide Vite `import.meta.glob(..., { query: '?raw' })` and eagerly bundled text files (including native Kotlin, tests, scripts, Rust and docs) into APK JavaScript. No WebView debugging enablement was present in MainActivity; this was an intentional UI source browser, not a DevTools leak.
- Created PR #38 at https://github.com/esi2ei/meshnet/pull/38 on branch `security/remove-bundled-source-viewer`, final head `06e11fc8d69eddd68b489f3c8c6d3d8591fa000d`, base `e269bc0b3e2c231b027782ffd9657505e9c4aa50`. It removes CodeExporter/UI, changes instrumentation to reject known repository-source markers and .map files, explicitly disables Vite source maps, updates README/architecture, and adds a narrow same-repo PR artifact retention exception for this fix APK.
- PR #38 is intentionally Draft pending exact-head CI. Run #247 (`46157922019`) on the final head: Node 22 typecheck and production build passed; Android job still in progress at `Build debug APK` at closeout; no Android build/signature/instrumentation result yet. Earlier runs #245/#246 were canceled because the branch was updated and are not authoritative.
- Current PR #38 head and base were rechecked; PR mergeable was true but GitHub mergeability state unstable. PR was converted back to Draft to prevent unsafe merge while CI is incomplete. No merge of PR #38 has been done; do not claim the fixed APK is available until run #247 completes and artifact is verified.
- Source-browser removal prevents the application from embedding a full browsable repository snapshot; it cannot prevent reverse engineering of a public repository or compiled JavaScript/Android bytecode. Full Android instrumentation remains manual-only in workflow; physical-device testing is not represented as passed.


## New user-authorized completion mission — 2026-11-28
- User explicitly requested: fix all remaining MeshNet repository problems and perform merges. Revalidate all live facts from GitHub; previous checkpoints are historical.
- Preserve safe repository operations: no force-push, no protection bypass, no suppression of required checks. Merge only current-head validated PRs. Separate objectively fixable code/docs/CI defects from hardware, signing-key, independent-audit and owner-decision gates that cannot honestly be fabricated.
- Start state: memory was read from `assistant-memory` at blob `6469d1da04a07d4b41d5fe58dc35835ee7b6ed68`. New execution begins now; first action is fresh live GitHub revalidation. Previous source-viewer PR #38 and run #247 details are historical until rechecked.
