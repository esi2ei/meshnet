import { CodeFile } from '../types';

export const FLUTTER_CODEBASE: CodeFile[] = [
  {
    name: 'README.md',
    filepath: 'mobile/README.md',
    language: 'markdown',
    content: `# Native Android prototype

The executable Android source is maintained under mobile/app. This explorer intentionally no longer exports the old pseudo-Flutter, pseudo-Kotlin, or pseudo-Swift snippets: those files contained placeholder cryptography, fake wallets, and non-buildable platform code.

The Android source now includes BLE/GATT messaging, optional Wi-Fi Aware and user-controlled Wi-Fi Direct transports, bounded resumable file transfer, voice notes, opt-in best-effort live group voice, Android Keystore-backed credential storage, and Devnet wallet authorization only. These are source implementations, not validated device capabilities.

Per-device identity, Double Ratchet/forward secrecy, secure key rotation, authenticated per-recipient delivery, production wallet transactions, and a deployed/audited escrow program are not implemented. See mobile/README.md, mobile/RELEASE_TEST_PLAN.md, and mobile/SECURITY_AND_ESCROW.md for the validation checklist and release gates.`
  }
];
