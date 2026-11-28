# ESI Assistant Memory — MeshNet

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
- Do not merge PRs or make irreversible project changes without explicit authorization.
- Before implementation, compare this requirements record with actual repository state and split work into coherent GitHub issues/PRs.

## Working style
- User prefers Persian.
- During requirements discovery, ask one question at a time unless the user asks for all remaining questions together.
- User has now asked to transfer the accumulated decisions and remaining proposed requirements into GitHub issues.
