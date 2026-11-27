# MeshNet

MeshNet is being developed as an offline-first peer-to-peer messaging and connectivity project. The repository currently contains two distinct parts:

- Web dashboard (src/): a React/Vite UI prototype and code workspace. Its simulated nodes and topology are not connected to phone radios and must not be treated as live network status.
- Native Android prototype (mobile/): the first BLE/GATT implementation, with encrypted text frames and TTL-based relay. See mobile/README.md for setup and limitations.

## Web dashboard

Requirements: Node.js 20+.

    npm install
    npm run dev

The dashboard does not require a Gemini API key for its core UI. Do not put private API keys in client-side environment variables.

## Native Android

Requirements: JDK 17 and Android SDK Platform 35. See mobile/README.md.

## Development status

The Android BLE prototype is an initial transport milestone, not a production release. Group-key bootstrap, durable offline queue, background execution, large-file transfer, Wi-Fi transports, iOS, and payment/escrow remain outstanding. The browser dashboard remains a simulator and is not proof of real radio connectivity.

No automated CI workflow is run for feature-branch pushes. The workflow is limited to main pushes, pull requests targeting main, and manual dispatch.
