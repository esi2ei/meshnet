# MeshNet Solana escrow program

This directory contains a first-pass non-custodial USDC order escrow program using Anchor 1.2.2.

## State machine

- `initialize_config`: one-time PDA configuration. The initial signer pins the USDC mint and dispute arbitrator. There is no config mutation instruction.
- `open_order`: buyer deposits an exact USDC amount into an order-specific associated token account controlled by the order PDA. Provider, amount, mint, and expiry are immutable.
- `release`: buyer and provider must both sign.
- `refund_expired`: buyer can refund after expiry.
- `arbitrate_release` / `arbitrate_refund`: the configured arbitrator can resolve a dispute.

The program rejects zero amounts, self-orders, expiry outside the next 30 days, non-canonical cluster USDC mints, repeated settlement, and underfunded vaults. It uses SPL Token (not Token-2022) and `TransferChecked`. The default Cargo feature pins Circle Devnet USDC (`4zMMC9...ncDU`). A Mainnet build must use `--no-default-features --features mainnet`, which pins Circle Mainnet USDC (`EPjFW...TDt1v`); selecting both cluster profiles fails compilation.

Settled order PDAs remain open so the terminal `Released` or `Refunded` status persists and can be queried. The buyer may later call `close_settled_order` to reclaim the order-account rent; open orders cannot be closed. The vault token account is still closed during settlement.

## Deployment is intentionally disabled

The declared program ID is Anchor's example ID and MUST be replaced with a newly generated program keypair before building a deployable artifact. The `INITIAL_ADMIN` constant is also a deliberately unusable system-program placeholder; replace it with the audited deployment administrator's public key before building. This hardcoded gate prevents an arbitrary first caller from front-running one-time config initialization. The program is not deployed and the config PDA has not been initialized. Android now has a Solana Devnet Mobile Wallet Adapter authorization/deauthorization flow, but it does not construct, sign, or submit an escrow transaction; no RPC endpoint, escrow program ID, or deployment key is connected to the app.

The config initializer accepts a mint address from the initial admin. Before any deployment, verify the exact canonical USDC mint for the target cluster independently and initialize config with that address. The configured arbitrator is a single key in this first iteration; production requires a reviewed multisig/threshold governance arrangement and a published dispute policy.

## Critical review required before funds

This source is an implementation draft, not an audited financial contract. Before accepting funds:
1. Replace the example program ID and reproduce the build.
2. Add Anchor/LiteSVM adversarial tests for all signer, PDA, token mint, amount, expiry, replay, and settlement paths.
3. Have an independent Solana security firm review the source and deployed binary.
4. Establish multisig governance for the arbitrator key, a key-loss/replacement plan, and a transparent dispute process.
5. Verify canonical USDC mint, token program, cluster identity, upgrade authority, and deployed program hash.
6. Add a typed Android/desktop client that derives PDAs locally, simulates transactions, displays exact token units, and verifies finalised signatures.

No production payment or escrow feature is enabled in MeshNet. Do not send real funds to this program.
