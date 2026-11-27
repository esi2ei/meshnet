import { CodeFile } from '../types';

export const FLUTTER_CODEBASE: CodeFile[] = [
  {
    name: 'README.md',
    filepath: 'mobile/README.md',
    language: 'markdown',
    content: `# Native Android prototype

The executable Android source is maintained under mobile/app. This explorer intentionally no longer exports the old pseudo-Flutter, pseudo-Kotlin, or pseudo-Swift snippets: those files contained placeholder cryptography, fake wallets, and non-buildable platform code.

See the repository mobile/README.md for the real BLE/GATT prototype and its explicit limitations. Wi-Fi transports, iOS, durable background relaying, file/voice transfer, Signal Protocol, and Solana escrow are not implemented yet.`
  }
];
