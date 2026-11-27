import React from 'react';
import { AlertTriangle, Bluetooth, Smartphone, ShieldAlert } from 'lucide-react';

interface PhoneWorkspaceProps {
  activeNodeId: string;
}

export default function PhoneWorkspace({ activeNodeId }: PhoneWorkspaceProps) {
  return (
    <section className="w-full min-h-[620px] rounded-[2rem] border border-slate-700 bg-[#101318] p-5 flex flex-col gap-5 shadow-2xl">
      <div className="text-center">
        <div className="mx-auto mb-3 w-14 h-14 rounded-2xl bg-cyan-500/10 border border-cyan-500/30 flex items-center justify-center">
          <Smartphone className="w-7 h-7 text-cyan-400" />
        </div>
        <h2 className="text-white font-semibold">Native device runtime</h2>
        <p className="text-xs text-slate-500 mt-1">Selected browser demo node: {activeNodeId}</p>
      </div>

      <div className="rounded-xl border border-amber-500/30 bg-amber-500/5 p-4">
        <div className="flex items-center gap-2 text-amber-300 text-sm font-semibold">
          <AlertTriangle className="w-4 h-4" />
          Browser controls are not connected to phone hardware
        </div>
        <p className="text-xs leading-relaxed text-slate-400 mt-2">
          This browser view is illustrative only. It does not send BLE packets, deliver chat messages, measure battery, transfer files or voice, connect a wallet, or execute escrow transactions.
        </p>
      </div>

      <div className="rounded-xl border border-slate-800 bg-[#0A0B0D] p-4">
        <div className="flex items-center gap-2 text-cyan-300 font-semibold text-sm">
          <Bluetooth className="w-4 h-4" />
          Real Android BLE prototype
        </div>
        <ol className="list-decimal list-inside space-y-2 text-xs text-slate-300 mt-3">
          <li>Open the repository's <code>mobile/</code> directory on a machine with JDK 17 and Android SDK 35.</li>
          <li>Build locally with <code>gradle assembleDebug</code>.</li>
          <li>Install the APK on two or more BLE-capable Android phones.</li>
          <li>Enter the same high-entropy group passphrase on each phone, grant Bluetooth and notification permissions, and start BLE mesh.</li>
          <li>Send a short encrypted text message. Use a third phone as a relay for a multi-hop experiment.</li>
        </ol>
      </div>

      <div className="rounded-xl border border-red-500/25 bg-red-500/5 p-4 mt-auto">
        <div className="flex items-center gap-2 text-red-300 text-sm font-semibold">
          <ShieldAlert className="w-4 h-4" />
          Not production-ready
        </div>
        <p className="text-xs leading-relaxed text-slate-400 mt-2">
          The Android prototype currently uses a shared AES-GCM group key and group-level receipts. It does not yet provide per-device identity, forward secrecy, secure key rotation, or production escrow. The browser screen is not a device emulator. See mobile/SECURITY_AND_ESCROW.md for the implementation plan and security gates.
        </p>
      </div>
    </section>
  );
}
