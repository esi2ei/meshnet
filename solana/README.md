# MeshNet Solana escrow program

This directory contains a first-pass non-custodial USDC order escrow program using Anchor 1.2.2.

## State machine

- `initialize_config`: one-time PDA configuration. The initial signer pins the USDC mint and dispute arbitrator. There is no config mutation instruction.
- `open_order`: buyer deposits an exact USDC amount into an order-specific associated token account controlled by the order PDA. Provider, amount, mint, and expiry are immutable.
- `release`: buyer and provider must both sign.
- `refund_expired`: buyer can refund after expiry.
- `arbitrate_release` / `arbitrate_refund`: the configured arbitrator can resolve a dispute.

The program rejects zero amounts, self-orders, expiry outside the next 30 days, non-canonical cluster USDC mints, repeated settlement, and underfunded vaults. It uses SPL Token (not Token-2022) and `TransferChecked`. The default Cargo feature pins Circle Devnet USDC (`4zMMC9...ncDU`). A Mainnet build must use `--no-default-features --features mainnet`, which pins Circle Mainnet USDC (`EPjFW...TDt1v`); selecting both cluster profiles fails compilation. Every build also requires the program/admin public-key environment variables described below.

Settled order PDAs remain open so the terminal `Released` or `Refunded` status persists and can be queried. The buyer may later call `close_settled_order` to reclaim the order-account rent; open orders cannot be closed. The vault token account is still closed during settlement.

## Deployment is intentionally disabled

The program has no default/example program ID or administrator key. Its build script requires both `MESHNET_ESCROW_PROGRAM_ID` and `MESHNET_ESCROW_INITIAL_ADMIN` and generates the Anchor ID declaration from those values. Missing or malformed values stop the build instead of allowing an example identity to enter a release artifact. Set the program ID to the public key derived from the dedicated program keypair, and set the administrator to the public key of the signer intended to initialize the config PDA. Keep the corresponding private keys outside the repository and build logs. For Anchor localnet workflows, add a `[programs.localnet]` mapping whose `meshnet_escrow` value exactly matches `MESHNET_ESCROW_PROGRAM_ID`.

Generate the program keypair locally (the path is covered by the repository ignore rules):

    mkdir -p target/deploy
    solana-keygen new --no-bip39-passphrase --outfile target/deploy/meshnet_escrow-keypair.json

Set the public-key build inputs. The administrator must be the public key of the wallet that will sign `initialize_config`; keep its private key outside the repository:

    export MESHNET_ESCROW_PROGRAM_ID="$(solana-keygen pubkey target/deploy/meshnet_escrow-keypair.json)"
    export MESHNET_ESCROW_INITIAL_ADMIN="$(solana-keygen pubkey ~/.config/solana/id.json)"

For a localnet Anchor workflow, add this mapping to `Anchor.toml` using the exact generated program ID:

    [programs.localnet]
    meshnet_escrow = "<value of MESHNET_ESCROW_PROGRAM_ID>"

The provider wallet configured under `[provider]` must correspond to the administrator public key when initializing the config. Do not commit the generated program keypair, administrator keypair, wallet file, or any seed phrase.

The program is not deployed and the config PDA has not been initialized. Android has a Solana Devnet Mobile Wallet Adapter authorization/deauthorization flow, but it does not construct, sign, or submit an escrow transaction; no RPC endpoint or deployed escrow program is connected to the app.

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
