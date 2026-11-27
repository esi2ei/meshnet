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
            This is not a blockchain explorer, radio monitor, or cryptographic audit log.
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
            <AlertTriangle className="w-4 h-4" /> Verified implementation boundaries
          </div>
          <p className="leading-relaxed">The native Android target currently contains BLE advertising/scanning, GATT peer connections, AES-GCM group-encrypted text frames, TTL relay, and process-local duplicate suppression.</p>
          <div className="flex items-center gap-2 text-cyan-300 font-semibold">
            <Radio className="w-4 h-4" /> Not yet implemented
          </div>
          <ul className="list-disc list-inside space-y-1">
            <li>Signal Protocol / per-device identity / forward secrecy</li>
            <li>Persistent offline queue and background foreground-service lifecycle</li>
            <li>Large-file and voice transfer, fragmentation and resume</li>
            <li>Wi-Fi Aware, Wi-Fi Direct and hotspot metering</li>
            <li>iOS runtime and real Solana payment/escrow</li>
          </ul>
          <div className="flex items-center gap-2 text-red-300 font-semibold">
            <ShieldAlert className="w-4 h-4" /> Security warning
          </div>
          <p className="leading-relaxed">Do not use the prototype for sensitive or financial data until peer authentication, key lifecycle, persistence, and independent security review are complete.</p>
        </div>
      </div>
    </div>
  );
}
