import React from 'react';
import { BlockchainLog } from '../types';
import { Terminal, AlertTriangle, Radio, ShieldAlert } from 'lucide-react';

interface SystemInfoProps {
  logs: BlockchainLog[];
  onClearLogs: () => void;
}

export default function SystemInfo({ logs, onClearLogs }: SystemInfoProps) {
  return (
    <div className="flex flex-col h-full bg-[#15171C] border border-slate-800 rounded-3xl overflow-hidden shadow-2xl">
      <div className="p-4 border-b border-slate-800 flex items-center justify-between gap-3">
        <div>
          <h2 className="text-sm font-semibold text-white uppercase tracking-wider flex items-center gap-2">
            <Terminal className="w-4 h-4 text-cyan-400" />
            Browser demonstration log
          </h2>
          <p className="text-[11px] text-amber-300 mt-1">
            Demo-only events; not a blockchain explorer, radio monitor, or cryptographic audit log.
          </p>
        </div>
        <button onClick={onClearLogs} className="text-[11px] text-slate-300 border border-slate-800 px-3 py-1 rounded-lg hover:bg-slate-900">
          Clear
        </button>
      </div>
      <div className="grid grid-cols-1 md:grid-cols-2 flex-1 min-h-0">
        <div className="p-4 overflow-y-auto border-b md:border-b-0 md:border-r border-slate-800">
          <div className="text-xs text-slate-400 mb-3">Demo events ({logs.length})</div>
          {logs.length === 0 ? (
            <p className="text-xs text-slate-500">No events. Simulated UI activity is not recorded as a real device or chain event.</p>
          ) : logs.map((entry) => (
            <div key={entry.id} className="mb-2 rounded-lg bg-[#0A0B0D] border border-slate-800 p-3 text-xs">
              <div className="text-cyan-300 font-mono">{entry.event}</div>
              <div className="text-slate-300 mt-1">{entry.details}</div>
              <div className="text-slate-500 mt-1">{entry.timestamp} · simulated {entry.network} label · {entry.txHash}</div>
            </div>
          ))}
        </div>
        <div className="p-4 space-y-4 text-xs text-slate-400">
          <div className="flex items-center gap-2 text-amber-300 font-semibold">
            <AlertTriangle className="w-4 h-4" /> Implemented in source — validation pending
          </div>
          <ul className="list-disc list-inside space-y-1">
            <li>Android BLE advertising/scanning, GATT transport, AES-GCM group-encrypted frames, TTL relay, persistent SQLite outbox and replay cache</li>
            <li>Optional Wi-Fi Aware discovery/data paths and user-triggered Wi-Fi Direct transport</li>
            <li>Bounded 10 MiB resumable file transfer with checkpoints, cancellation, SHA-256 verification and inbox quotas</li>
            <li>Voice notes and opt-in best-effort live group voice using encrypted ephemeral frames</li>
            <li>Android Keystore-protected group credentials and user-authorized Solana Devnet wallet authorization</li>
          </ul>
          <div className="flex items-center gap-2 text-cyan-300 font-semibold">
            <Radio className="w-4 h-4" /> Not implemented / not production-ready
          </div>
          <ul className="list-disc list-inside space-y-1">
            <li>Authenticated per-device identity, Double Ratchet sessions, forward secrecy and secure group-key rotation</li>
            <li>Authenticated membership roster, per-recipient delivery ledger and destination-aware routing based on measured radio metrics</li>
            <li>Wallet transaction construction, signing or submission; escrow deployment, independent audit and transaction integration</li>
            <li>iOS runtime (deferred)</li>
          </ul>
          <div className="flex items-center gap-2 text-red-300 font-semibold">
            <ShieldAlert className="w-4 h-4" /> Validation and security warning
          </div>
          <p className="leading-relaxed">The browser topology is a simulation; only the Android device-runtime panel is connected to native services. CI typechecks and builds the web dashboard, packages and signature-verifies the Android APK, and runs instrumentation tests on an API 36 emulator. Consult the latest GitHub Actions run for the current result. Emulator tests do not establish physical-device Bluetooth/Wi-Fi interoperability. The escrow source has not been built, tested, deployed or audited. Do not use this prototype for sensitive or financial traffic before independent security review and device testing.</p>
        </div>
      </div>
    </div>
  );
}
