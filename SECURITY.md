# Security Policy

## Supported development line

Security fixes are applied to the active development branch and then promoted through reviewed pull requests. This repository is a prototype; do not rely on it for confidential, safety-critical, or financial traffic.

## Reporting a vulnerability

Please do not publish exploitable details in a public issue or pull request. Use GitHub's **Report a vulnerability** flow in the repository's Security tab so maintainers can review the report privately. Include the affected commit, Android/API version, reproduction steps, expected impact, and any relevant logs with secrets removed.

Do not include group passphrases, wallet authorization tokens, private keys, keystores, signing passwords, personal files, or unredacted device identifiers in reports.

## Current high-risk limitations

- Shared group AES-GCM is a prototype and does not provide authenticated per-device identity, forward secrecy, secure group-key rotation, or named-recipient delivery receipts.
- The WebView bridge is exposed only to the bundled dashboard served by AndroidX WebViewAssetLoader at the virtual appassets origin. External navigation, file access, mixed content, and WebView-origin network connections are disabled. Do not weaken its host/path allowlist or Content Security Policy.
- Wallet authorization is limited to an explicitly enabled, user-initiated Solana Devnet flow. No real payment or escrow transaction flow is implemented.
- Debug APKs and emulator tests are not production security certification. Physical-device testing and independent review remain required.
