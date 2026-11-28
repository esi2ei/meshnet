# Distributed governance, reputation, marketplace and arbitration — design gates

Status: design specification only; none of these four subsystems is implemented end-to-end.
Related issues: #20, #21, #23, #26, with cross-cutting dependency #18 (identity/trust), #24 (contracts/receipts), #25 (wallet), and #28 (traceability).

This document defines the decisions and security invariants that must be settled before implementation. It deliberately does not claim that a data class, mock UI, prototype escrow program or locally signed record is a working decentralized product feature.

## 1. Shared prerequisites and invariants

### 1.1 Device identity is not a user identity

The current app has a per-install P-256 signing identity and a group credential shared by participants. This is sufficient to authenticate that a frame was signed by a particular installation key, but it does not establish:
- that the key belongs to a unique human or organization;
- that one user cannot create many device keys;
- that a device is approved by a channel/group;
- that the current replica has a complete view of every member;
- that an offline payment or vote has global uniqueness.

Before governance, reputation or arbitration can use the word *eligible*, MeshNet needs an explicit user identity and device-enrollment model. A candidate direction is a user-controlled account signing key that certifies one or more device keys, with signed revocation and portable encrypted recovery. The group-owner/invitation authority and recovery model must be explicit; do not silently promote the first connected peer to administrator.

The current shared group passphrase permits a participant to generate additional P-256 device identities. No reputation score or one-device-per-installation rule can honestly claim Sybil resistance while admission remains based only on shared-secret possession.

### 1.2 Offline state is replicated, not globally agreed

SQLite event logs and signed records can provide integrity and deterministic local replay. They do not provide consensus or prove that a partitioned replica has seen every event.

All four systems must:
- use canonical, versioned, domain-separated signed records;
- include event IDs, actor identity, monotonic sequence/version, creation time and expiry where relevant;
- preserve conflicting records/equivocation instead of last-write-wins;
- reconcile bounded pages after reconnection, with explicit stale/incomplete status;
- use transactional persistence and back-pressure rather than silently evicting records;
- distinguish locally observed decisions from globally finalized decisions;
- provide export/recovery before bounded stores refuse additional data.

A device must never be treated as eligible merely because it appears in a locally cached roster.

### 1.3 No hidden cost, punishment or finality

A product action with financial, governance or access consequences requires a visible, signed policy and an appeal/recovery route. Unverified reports are evidence submissions, not findings. Offline vouchers, service receipts and Devnet transfers are not final fiat settlement. A relay or host must not be able to read private user evidence or private file metadata.

## 2. Issue #20 — public-channel governance

### 2.1 Lifecycle and signed event model

A channel governance record should be a signed, append-only event stream. Candidate event types:
- `CHANNEL_CREATED`: channel ID, creator account identity, initial policy hash and creation time;
- `PROPOSAL_OPENED`: proposal ID, policy version, action, bounded payload hash, proposer and voting window;
- `VOTE_CAST`: proposal ID, eligible account identity, choice, nonce and policy version;
- `COMPLAINT_OPENED`: manager/subject, allegation category, evidence commitments and response deadline;
- `RESPONSE_SUBMITTED`: subject response and evidence commitments;
- `MANAGER_SUSPENDED`: reversible temporary removal of manager privileges during a review;
- `MANAGER_ELECTED`, `MANAGER_REPLACED`, `PROPOSAL_FINALIZED`, `PROPOSAL_APPEALED`, `APPEAL_RESOLVED`.

Each event must bind to the exact preceding channel-state checkpoint. Duplicate votes, repeated event IDs, conflicting counters and stale policy versions must be rejected or retained as explicit equivocation, never resolved by last-write-wins.

### 2.2 Eligibility, voting and deterministic outcomes

The implementation must specify before code is written:
- whether eligibility is per user account or per device (per-device voting is not acceptable as a Sybil defense);
- minimum membership/participation and activity windows;
- how endorsements and verified conduct affect creation quotas;
- the initial quota (the requested starting model is one public channel per eligible user);
- voting window, quorum, tie/no-quorum handling, abstention and vote replacement;
- manager inactivity measurement and replacement election;
- when temporary suspension is permitted, its expiry, evidence threshold and right of response;
- how a channel continues when peers remain offline or replicas have different event-log checkpoints.

No manager or replica may unilaterally erase an election, permanently ban an account or rewrite a completed vote. Suspension must be reversible and must not be represented as a finding of guilt.

### 2.3 Required acceptance tests

- duplicate/replayed vote, stale policy version and counter equivocation;
- two devices belonging to one account cannot cast two votes;
- a partitioned replica reports stale/incomplete governance state rather than a false final result;
- quorum failure and tie leave a proposal unresolved under an explicit policy;
- offline manager timeout and replacement are deterministic across replicas;
- suspended manager can respond and appeal; suspension expiry restores privileges unless a final, appealable decision exists;
- malicious coordinator cannot forge, suppress or reorder signed decisions;
- event-store capacity uses back-pressure and export/recovery, not silent eviction.

**Current status:** no complete governance lifecycle or admission/eligibility implementation is present. Issue #20 remains open. This design does not create an administrator key, quorum policy or anti-Sybil claim by implication.

## 3. Issue #21 — reputation, trust endorsements and abuse resistance

### 3.1 Evidence categories

Keep separate, typed records for:
1. **Observed interaction:** a mutually signed service/message/transfer event with an exact contract or message reference.
2. **Endorsement:** an account identity explicitly endorses another account for a stated scope and validity interval.
3. **Report:** an allegation with optional evidence commitment; it is unverified by default.
4. **Finding:** a decision by an authorized, appealable review process that references the evidence and policy version.
5. **Appeal:** a subject's challenge and its resolution.

An unsigned UI reaction or a single peer's unverified report must never change a security-sensitive eligibility bit. An endorsement must include signer, subject, scope, timestamp, expiry, nonce and signature. It must be revocable and rate-limited.

### 3.2 Scoring constraints

Do not make one universal scalar that conflates delivery reliability, financial trust, governance eligibility and moderation. Use domain-specific, explainable components with:
- bounded contribution per identity and per time window;
- aging/decay for old behavior;
- separation of provisional offline observations from reconciled facts;
- no bonus for reciprocal endorsements within a closed cluster without independent evidence;
- privacy-preserving disclosure (show only what the decision needs);
- explicit appeal and correction for mistaken or compromised identities.

A score may recommend a review or limit a low-risk quota. It must not automatically impose irreversible punishment, seize funds or erase content. The exact weights and thresholds require owner/product decision and adversarial simulation; they are not selected here.

### 3.3 Threat model and tests

Test Sybil account farms, reciprocal endorsement rings, colluding relays, brigading, malicious false reports, compromised signing keys, expired/revoked devices, clock skew, partitioned replicas and score manipulation. Verify that:
- reports remain visibly unverified until a finding exists;
- the subject can respond and appeal;
- repeated devices under one account do not multiply weight;
- a single actor cannot dominate through high-frequency low-value interactions;
- deleted/expired evidence does not remain exposed to peers;
- reputation cannot authorize a private file read, override a signed contract or substitute for payment finality.

**Current status:** device-origin signatures are not a reputation system. No complete endorsement, scoring, moderation or appeal implementation exists. Issue #21 remains open.

## 4. Issue #23 — service marketplace, metering and optional Internet uplink

### 4.1 Keep the present private overlay boundary

The current VPN is an opt-in private `100.64.0.0/10` intranet. It is not a general Internet gateway and does not NAT arbitrary device traffic. Do not change that route or advertise public Internet access until the gateway, consent, isolation, carrier/legal and device-validation gates below pass.

Internet uplink is an optional service, not a prerequisite for local mesh discovery, chat, local content or local services.

### 4.2 Offer and contract model

A provider offer must be signed by the provider account/device identity and include:
- service type and exact network capability (private overlay, selected destination, or explicitly authorized egress);
- public rate/asset/rounding rule and valid-from/until;
- maximum bytes, maximum cost, budget/stop behavior and cancellation policy;
- permitted destination/protocol scope and any traffic limits;
- relay share and each relay's exact agreed percentage;
- legal/carrier/OS compatibility disclosure and privacy consequences.

A consumer explicitly accepts the exact offer and terms. Every relay must sign the same relay-split version before it becomes active. An active price or split cannot be modified unilaterally. Route selection may optimize total accepted cost only after it has a trusted offer, security policy and measured link-quality input; shortest-hop routing is not economic routing.

### 4.3 Metering boundary and settlement

A signed contract must define *billable application payload bytes* at the receiver's accepted-payload boundary. Transport headers, routing metadata, unrelated system traffic and unaccepted/rejected bytes are excluded. Retransmission policy and partial-chunk rules must be explicit. The receiver computes size and digest from actual accepted bytes, then both parties sign a monotonic receipt chain. The receipt journal, live transfer lifecycle and wallet ledger must update idempotently in one recoverable protocol.

Offline internal credit can only be provisional, explicitly capped and later reconciled with conflict/double-spend disclosure. Fiat is pending until an online payment provider confirms it; model confirmed, failed, reversed and disputed states. A local voucher, signed receipt or Devnet SOL transfer is not settled fiat.

### 4.4 Network, privacy and legal gates

Before any real Internet gateway/NAT or resale:
- review carrier/tethering terms, national/regional law and applicable platform-store policies for each deployment territory;
- obtain explicit provider and consumer consent for each active egress path;
- isolate per-consumer traffic and prevent lateral access to provider/other clients;
- prevent DNS leaks and restrict destinations/protocols according to the signed offer;
- provide immediate stop/revoke, cost caps and abuse controls;
- define provider liability, illegal traffic/reporting, logging minimization and retention;
- test radio loss, gateway disappearance, address changes, NAT state expiry, malicious consumers and quota exhaustion on physical devices.

Do not infer a legal permission from the fact that Android can create a VPN or local TCP socket.

### 4.5 Acceptance tests

- no public traffic leaves a device unless the owner explicitly enabled an offer;
- no offer can be activated without provider/consumer/relay signatures on identical terms;
- accepted payload digest and byte total reconcile across sender/receiver after interruption;
- duplicate/replayed receipts cannot bill twice;
- actual cost never exceeds the signed cap; a full journal causes back-pressure;
- route selection never picks an unaccepted, insecure or over-budget provider;
- gateway loss pauses/stops without silently charging unaccepted traffic;
- privacy isolation and carrier/legal approval are separately evidenced.

**Current status:** no gateway/NAT, live metering, offer lifecycle, economic router or real-money settlement is implemented. Issue #23 remains open. The private intranet must not be marketed as Internet sharing.

## 5. Issue #26 — peer arbitration and dispute resolution

### 5.1 Dispute state machine

A dispute must reference an exact signed contract version and receipt-chain checkpoint. Candidate states:
`OPENED` → `EVIDENCE_FROZEN` → `ARBITRATORS_SELECTED` → `VOTING` → `DECIDED` → `APPEALED` → `FINALIZED`.

Opening a dispute pauses the contested settlement and preserves the minimum necessary evidence. Neither party nor one arbitrator can release/refund funds or apply a penalty while the dispute is unresolved. A timeout is not a decision.

### 5.2 Arbitrator selection and quorum

The policy must define:
- eligibility based on a verifiable account identity and non-conflicted role;
- exclusion of parties, relays directly involved in the transfer and declared conflicts;
- a verifiable selection input and replay-resistant selection transcript;
- a minimum quorum and decision threshold;
- timeout, replacement and the no-quorum result;
- evidence privacy, retention and right of response;
- an appeal/review path with a fresh, independent panel;
- deterministic mapping from decision to contract-authorized release, refund or penalty.

A locally generated random number or a single hard-coded arbitrator is not verifiable decentralized selection. A commit/reveal or verifiable-randomness design must be evaluated against offline partitions, biased reveals, collusion and Sybil identities before choosing it. If there is no trustworthy entropy source or quorum, the correct outcome is **unresolved / funds remain pending**, not an invented majority.

### 5.3 Financial and program boundary

The current Anchor escrow source is a prototype: it is not deployed, independently audited, connected to the Android transfer receipt lifecycle, or approved for real funds. A production arbitration workflow must be integrated with a reviewed settlement program and exact token mint/program IDs. Never treat a local vote or test validator result as fiat settlement.

### 5.4 Acceptance tests

- offline/nonresponsive arbitrator replacement and insufficient quorum;
- conflicting, replayed and equivocated votes;
- malicious arbitrator, collusion, party/relay conflict and Sybil panels;
- timeout, late reveal and biased-randomness attack;
- evidence minimization, tamper detection and confidentiality;
- appeal with a fresh panel and preserved original transcript;
- refund/release/penalty race, duplicate instruction and idempotent finalization;
- no funds move while quorum is absent or evidence is invalid.

**Current status:** no complete peer-arbitration workflow, verified selection, quorum, appeal or tested settlement integration exists. Issue #26 remains open.

## 6. Dependency-ordered delivery

1. **Identity/trust prerequisite (#18):** define user/account identity, device enrollment, revocation and portable recovery; do not claim Sybil resistance before this works.
2. **Durable verified events (#28):** encrypted persistence, bounded sync with crash-resumable cursors, equivocation retention, stale-state UI and export/recovery.
3. **Governance/reputation (#20/#21):** implement on the verified account/event foundation, then adversarial simulations and appeals.
4. **Contracts and metering (#24/#25):** integrate receiver-measured accepted payloads, mutual receipt exchange, wallet reconciliation and explicit pending/final states.
5. **Marketplace/gateway (#23):** only after legal/carrier review, per-peer isolation, explicit consent and physical-device traffic tests.
6. **Arbitration/escrow (#26):** only after account eligibility, signed contract/receipt reconciliation, reviewed settlement program and independent audit.

## 7. Evidence rule

Each GitHub issue remains open until its acceptance criteria link to:
- implementation path and exact commit;
- regression test and passing current-head CI run;
- for physical radio/gateway claims: device models, Android/OEM builds, topology, test steps, logs and measured results;
- for identity/crypto/escrow/legal claims: independent reviewer, deployed artifact or owner approval as applicable.

A design document, test source, mock UI, debug APK or passing emulator build is not completion evidence by itself.
