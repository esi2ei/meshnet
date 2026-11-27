import React, { useEffect, useState } from 'react';
import { Activity, Bluetooth, FileUp, FileDown, Mic, MicOff, Network, Power, Radio, Settings2, ShieldCheck, Wifi, Wallet } from 'lucide-react';

type NativeRuntimeStatus = {
  native: boolean;
  running: boolean;
  onlineIntegrationsEnabled: boolean;
  walletBusy: boolean;
  walletAddress: string | null;
  offlineWalletAddress: string | null;
  offlineWalletTransfers: Array<{ id: string; recipient: string; amountMinor: number; createdAt: number; status: string }>;
  offlineWalletReceipts: Array<{ id: string; sender: string; amountMinor: number; createdAt: number; asset: string }>;
  platform: string;
  recentEvents: string[];
};

type NativeBridge = {
  getRuntimeStatus: () => string;
  startMesh: (passphrase: string) => void;
  stopMesh: () => void;
  sendMessage: (message: string) => boolean;
  selectFile: () => void;
  cancelFileTransfer: () => boolean;
  saveReceivedFile: () => void;
  toggleVoiceNote: () => void;
  playVoiceNote: () => void;
  startLiveVoice: (join: boolean) => void;
  endLiveVoice: () => void;
  createWifiDirectGroup: () => void;
  discoverWifiDirect: () => void;
  connectWifiDirect: (address: string) => void;
  resumeWifiAware: () => void;
  connectWallet: () => void;
  disconnectWallet: () => void;
  sendSolanaDevnetTransfer: (recipientAddress: string, amountSol: string) => void;
  getOfflineWalletAddress: () => string;
  copyOfflineWalletAddress: () => boolean;
  sendOfflinePayment: (recipientAddress: string, amountMinor: number) => boolean;
  forgetGroupCredentials: () => void;
  setOnlineIntegrationsEnabled: (enabled: boolean) => void;
};

declare global {
  interface Window {
    MeshNetNative?: NativeBridge;
    MeshNetUI?: {
      onNativeEvent: (event: { message?: string; status?: NativeRuntimeStatus }) => void;
    };
  }
}

const initialStatus: NativeRuntimeStatus = {
  native: false,
  running: false,
  onlineIntegrationsEnabled: false,
  walletBusy: false,
  walletAddress: null,
  offlineWalletAddress: null,
  offlineWalletTransfers: [],
  offlineWalletReceipts: [],
  platform: 'browser',
  recentEvents: [],
};

const actionClass = 'inline-flex min-h-11 items-center justify-center gap-2 rounded-xl border border-slate-700 bg-slate-900 px-3 py-2 text-sm font-medium text-slate-100 transition hover:border-cyan-500/60 hover:bg-slate-800 disabled:cursor-not-allowed disabled:opacity-40';
const primaryClass = 'inline-flex min-h-11 items-center justify-center gap-2 rounded-xl border border-cyan-400/40 bg-cyan-500/15 px-3 py-2 text-sm font-semibold text-cyan-200 transition hover:bg-cyan-500/25 disabled:cursor-not-allowed disabled:opacity-40';

export default function PhoneWorkspace() {
  const [runtime, setRuntime] = useState(initialStatus);
  const [passphrase, setPassphrase] = useState('');
  const [message, setMessage] = useState('');
  const [wifiAddress, setWifiAddress] = useState('');
  const [offlineRecipient, setOfflineRecipient] = useState('');
  const [offlineAmount, setOfflineAmount] = useState('');
  const [solanaRecipient, setSolanaRecipient] = useState('');
  const [solanaAmount, setSolanaAmount] = useState('');
  const [lastEvent, setLastEvent] = useState('Waiting for native runtime');
  const native = typeof window !== 'undefined' ? window.MeshNetNative : undefined;

  const refresh = () => {
    try {
      if (window.MeshNetNative) {
        const parsed = JSON.parse(window.MeshNetNative.getRuntimeStatus()) as NativeRuntimeStatus;
        setRuntime(parsed);
      }
    } catch {
      setLastEvent('Could not read native runtime status');
    }
  };

  useEffect(() => {
    window.MeshNetUI = {
      onNativeEvent: (event) => {
        if (event.status) setRuntime(event.status);
        if (event.message) setLastEvent(event.message);
        refresh();
      },
    };
    refresh();
    const onVisible = () => refresh();
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      document.removeEventListener('visibilitychange', onVisible);
      delete window.MeshNetUI;
    };
  }, []);

  const canSend = runtime.native && runtime.running && message.trim().length > 0 &&
    new TextEncoder().encode(message).length <= 120;
  const onlineDescription = runtime.onlineIntegrationsEnabled
    ? 'Enabled by you. Wallet authorization is limited to Solana Devnet.'
    : 'Disabled. Local mesh, messaging and file transfer remain available.';

  return (
    <section className="w-full rounded-3xl border border-slate-700 bg-[#101318] p-4 sm:p-5 flex flex-col gap-4 shadow-2xl">
      <div className="flex items-start justify-between gap-3">
        <div className="flex items-center gap-3">
          <div className="flex h-12 w-12 shrink-0 items-center justify-center rounded-2xl border border-cyan-500/30 bg-cyan-500/10">
            <SmartphoneIcon />
          </div>
          <div>
            <h2 className="font-semibold text-white">Device runtime</h2>
            <p className="mt-1 text-xs text-slate-400">Local Android services · offline-first</p>
          </div>
        </div>
        <span className={`rounded-full border px-2.5 py-1 text-[11px] font-semibold ${runtime.native ? 'border-emerald-500/30 bg-emerald-500/10 text-emerald-300' : 'border-amber-500/30 bg-amber-500/10 text-amber-300'}`}>
          {runtime.native ? 'Native app' : 'Browser preview'}
        </span>
      </div>

      <div className="grid grid-cols-2 gap-2">
        <div className="rounded-xl border border-slate-800 bg-[#0A0B0D] p-3">
          <div className="flex items-center gap-2 text-xs text-slate-400"><Bluetooth className="h-4 w-4 text-cyan-400" /> Local mesh session</div>
          <div className={`mt-2 text-sm font-semibold ${runtime.running ? 'text-emerald-300' : 'text-slate-300'}`}>{runtime.running ? 'Running' : 'Stopped'}</div>
        </div>
        <div className="rounded-xl border border-slate-800 bg-[#0A0B0D] p-3">
          <div className="flex items-center gap-2 text-xs text-slate-400"><ShieldCheck className="h-4 w-4 text-cyan-400" /> Local security</div>
          <div className="mt-2 text-sm font-semibold text-slate-200">Encrypted group frames</div>
        </div>
      </div>

      {!runtime.native && (
        <div className="rounded-xl border border-amber-500/30 bg-amber-500/5 p-3 text-xs leading-relaxed text-amber-100">
          The interactive phone controls are available inside the Android APK. This browser preview does not have permission to control Bluetooth or Wi-Fi hardware.
        </div>
      )}

      <div className="space-y-2 rounded-xl border border-slate-800 bg-[#0A0B0D] p-3">
        <div className="flex items-center gap-2 text-sm font-semibold text-white"><Power className="h-4 w-4 text-cyan-400" /> Mesh controls</div>
        <label className="block text-xs text-slate-400" htmlFor="mesh-passphrase">Shared group passphrase (24+ characters)</label>
        <input id="mesh-passphrase" type="password" autoComplete="new-password" value={passphrase}
          onChange={(event) => setPassphrase(event.target.value)} placeholder="Leave blank to reuse the saved key"
          className="min-h-11 w-full rounded-lg border border-slate-700 bg-slate-950 px-3 text-sm text-white outline-none focus:border-cyan-400" />
        <div className="grid grid-cols-2 gap-2">
          <button className={primaryClass} disabled={!runtime.native} onClick={() => { native?.startMesh(passphrase); setPassphrase(''); }}>
            <Radio className="h-4 w-4" /> Start / resume
          </button>
          <button className={actionClass} disabled={!runtime.native || !runtime.running} onClick={() => native?.stopMesh()}>
            <Power className="h-4 w-4" /> Stop mesh
          </button>
          <button className={actionClass + ' col-span-2'} disabled={!runtime.native}
            onClick={() => native?.forgetGroupCredentials()}>
            Forget saved group credentials and queue
          </button>
        </div>
        <p className="text-[11px] leading-relaxed text-slate-500">The passphrase is sent directly to Android and is not saved in the web dashboard or browser storage. Enter the same high-entropy secret on every participating phone.</p>
      </div>

      <div className="space-y-2 rounded-xl border border-slate-800 bg-[#0A0B0D] p-3">
        <div className="flex items-center gap-2 text-sm font-semibold text-white"><Activity className="h-4 w-4 text-cyan-400" /> Encrypted messaging</div>
        <textarea value={message} onChange={(event) => setMessage(event.target.value)} rows={2} maxLength={120}
          placeholder="Message (up to 120 UTF-8 bytes)"
          className="w-full resize-y rounded-lg border border-slate-700 bg-slate-950 p-3 text-sm text-white outline-none focus:border-cyan-400" />
        <div className="flex items-center justify-between gap-2 text-[11px] text-slate-500">
          <span>{new TextEncoder().encode(message).length}/120 bytes</span>
          <button className={primaryClass} disabled={!canSend} onClick={() => {
            const accepted = native?.sendMessage(message.trim()) === true;
            setLastEvent(accepted ? 'Message accepted into the encrypted outbox' : 'Message was not accepted; check mesh state and message size');
            if (accepted) setMessage('');
          }}>Queue encrypted message</button>
        </div>
      </div>

      <div className="space-y-2 rounded-xl border border-slate-800 bg-[#0A0B0D] p-3">
        <div className="flex items-center gap-2 text-sm font-semibold text-white"><FileUp className="h-4 w-4 text-cyan-400" /> Files and voice</div>
        <div className="grid grid-cols-2 gap-2">
          <button className={actionClass} disabled={!runtime.native || !runtime.running} onClick={() => native?.selectFile()}><FileUp className="h-4 w-4" /> Send file</button>
          <button className={actionClass} disabled={!runtime.native} onClick={() => native?.saveReceivedFile()}><FileDown className="h-4 w-4" /> Save received</button>
          <button className={actionClass} disabled={!runtime.native || !runtime.running} onClick={() => native?.cancelFileTransfer()}>Cancel transfer</button>
          <button className={actionClass} disabled={!runtime.native || !runtime.running} onClick={() => native?.toggleVoiceNote()}><Mic className="h-4 w-4" /> Record voice note</button>
          <button className={actionClass} disabled={!runtime.native} onClick={() => native?.playVoiceNote()}><MicOff className="h-4 w-4" /> Play latest voice</button>
          <button className={actionClass} disabled={!runtime.native || !runtime.running} onClick={() => native?.startLiveVoice(false)}><Activity className="h-4 w-4" /> Start live voice</button>
          <button className={actionClass} disabled={!runtime.native || !runtime.running} onClick={() => native?.startLiveVoice(true)}>Join live voice</button>
          <button className={actionClass} disabled={!runtime.native} onClick={() => native?.endLiveVoice()}>End live voice</button>
        </div>
        <p className="text-[11px] leading-relaxed text-slate-500">File transfer is limited to 10 MiB per file. Microphone and file permissions are requested only after you invoke the corresponding action.</p>
      </div>

      <div className="space-y-3 rounded-xl border border-slate-800 bg-[#0A0B0D] p-3">
        <div className="flex items-center gap-2 text-sm font-semibold text-white"><Wifi className="h-4 w-4 text-cyan-400" /> Local radio options</div>
        <div className="grid grid-cols-2 gap-2">
          <button className={actionClass} disabled={!runtime.native || !runtime.running} onClick={() => native?.resumeWifiAware()}>Use Wi-Fi Aware</button>
          <button className={actionClass} disabled={!runtime.native || !runtime.running} onClick={() => native?.discoverWifiDirect()}>Discover Wi-Fi Direct</button>
          <button className={actionClass} disabled={!runtime.native || !runtime.running} onClick={() => native?.createWifiDirectGroup()}>Create Wi-Fi Direct group</button>
        </div>
        <label className="block text-xs text-slate-400" htmlFor="wifi-peer-address">Wi-Fi Direct peer address</label>
        <div className="flex gap-2">
          <input id="wifi-peer-address" value={wifiAddress} onChange={(event) => setWifiAddress(event.target.value)}
            placeholder="AA:BB:CC:DD:EE:FF" className="min-w-0 flex-1 rounded-lg border border-slate-700 bg-slate-950 px-3 text-sm text-white outline-none focus:border-cyan-400" />
          <button className={actionClass} disabled={!runtime.native || !runtime.running || !wifiAddress.trim()} onClick={() => native?.connectWifiDirect(wifiAddress.trim())}>Connect</button>
        </div>
      </div>

      <div className="space-y-3 rounded-xl border border-slate-800 bg-[#0A0B0D] p-3">
        <div className="flex items-center gap-2 text-sm font-semibold text-white"><Wallet className="h-4 w-4 text-cyan-400" /> Offline wallet-to-wallet transfer</div>
        <p className="break-all text-[11px] text-slate-400">This device address: {runtime.offlineWalletAddress || (native ? "Creating local key…" : "Available in the Android APK")}</p>
        <button className={actionClass} disabled={!runtime.native || !runtime.offlineWalletAddress} onClick={() => native?.copyOfflineWalletAddress()}>Copy my wallet address</button>
        <label className="block text-xs text-slate-400" htmlFor="offline-wallet-recipient">Recipient MeshNet wallet address (64 hex characters)</label>
        <input id="offline-wallet-recipient" value={offlineRecipient} onChange={(event) => setOfflineRecipient(event.target.value)}
          placeholder="Recipient local wallet address" className="min-h-11 w-full rounded-lg border border-slate-700 bg-slate-950 px-3 text-sm text-white outline-none focus:border-cyan-400" />
        <label className="block text-xs text-slate-400" htmlFor="offline-wallet-amount">Amount (integer minor units)</label>
        <input id="offline-wallet-amount" inputMode="numeric" value={offlineAmount} onChange={(event) => setOfflineAmount(event.target.value.replace(/[^0-9]/g, ''))}
          placeholder="e.g. 1250" className="min-h-11 w-full rounded-lg border border-slate-700 bg-slate-950 px-3 text-sm text-white outline-none focus:border-cyan-400" />
        <button className={primaryClass} disabled={!runtime.native || !runtime.running || !/^[0-9a-f]{64}$/i.test(offlineRecipient) || !/^[1-9][0-9]{0,12}$/.test(offlineAmount)}
          onClick={() => { const sent = native?.sendOfflinePayment(offlineRecipient.trim().toLowerCase(), Number(offlineAmount)) === true; setLastEvent(sent ? "Signed offline transfer queued · waiting for recipient acknowledgement" : "Offline wallet transfer was not queued"); if (sent) setOfflineAmount(""); }}>
          Send signed transfer over local mesh
        </button>
        <p className="text-[11px] leading-relaxed text-amber-200/80">The recipient verifies and saves the voucher, then returns a signed acknowledgement that the sender verifies. These are provisional local-unit receipts, not real currency or blockchain settlement. Offline software still cannot guarantee a global balance or prevent double-spending across disconnected devices. Do not use this prototype for money or valuable assets.</p>
        {(runtime.offlineWalletTransfers.length > 0 || runtime.offlineWalletReceipts.length > 0) && (
          <div className="space-y-2 border-t border-slate-800 pt-3">
            <div className="text-xs font-semibold text-slate-300">Recent wallet activity</div>
            {runtime.offlineWalletTransfers.slice().reverse().map((transfer) => (
              <div key={transfer.id} className="flex items-center justify-between gap-3 text-[11px]">
                <span className="min-w-0 truncate text-slate-400">Sent to {transfer.recipient.slice(0, 12)}… · {transfer.amountMinor} local units</span>
                <span className={transfer.status === 'RECEIVED' ? 'shrink-0 text-emerald-300' : 'shrink-0 text-amber-300'}>{transfer.status === 'RECEIVED' ? 'Recipient confirmed' : 'Awaiting acknowledgement'}</span>
              </div>
            ))}
            {runtime.offlineWalletReceipts.slice().reverse().map((receipt) => (
              <div key={receipt.id} className="text-[11px] text-emerald-300">
                Received {receipt.amountMinor} local units from {receipt.sender.slice(0, 12)}…
              </div>
            ))}
          </div>
        )}
      </div>

      <div className="space-y-3 rounded-xl border border-slate-800 bg-[#0A0B0D] p-3">
        <div className="flex items-center gap-2 text-sm font-semibold text-white"><Settings2 className="h-4 w-4 text-cyan-400" /> Online integrations</div>
        <label className="flex items-start gap-3 text-sm text-slate-200">
          <input type="checkbox" className="mt-1 h-4 w-4 accent-cyan-400" checked={runtime.onlineIntegrationsEnabled}
            disabled={!runtime.native} onChange={(event) => native?.setOnlineIntegrationsEnabled(event.target.checked)} />
          <span>Allow optional online integrations
            <span className="mt-1 block text-xs leading-relaxed text-slate-500">{onlineDescription}</span>
          </span>
        </label>
        <div className="flex flex-wrap gap-2">
          <button className={actionClass} disabled={!runtime.native || !runtime.onlineIntegrationsEnabled || runtime.walletBusy}
            onClick={() => native?.connectWallet()}><Wallet className="h-4 w-4" /> Connect Solana Devnet wallet</button>
          <button className={actionClass} disabled={!runtime.native || !runtime.walletAddress || runtime.walletBusy}
            onClick={() => native?.disconnectWallet()}>Disconnect wallet</button>
        </div>
        {runtime.walletAddress && <p className="break-all text-[11px] text-emerald-300">Authorized address: {runtime.walletAddress}</p>}
        {runtime.onlineIntegrationsEnabled && runtime.walletAddress && (
          <div className="space-y-2 border-t border-slate-800 pt-3">
            <div className="text-xs font-semibold text-slate-300">Send SOL on Devnet (online)</div>
            <label className="block text-xs text-slate-400" htmlFor="solana-devnet-recipient">Recipient Solana address</label>
            <input id="solana-devnet-recipient" value={solanaRecipient} onChange={(event) => setSolanaRecipient(event.target.value.trim())}
              placeholder="Devnet recipient address" className="min-h-11 w-full rounded-lg border border-slate-700 bg-slate-950 px-3 text-sm text-white outline-none focus:border-cyan-400" />
            <label className="block text-xs text-slate-400" htmlFor="solana-devnet-amount">Amount in SOL (up to 9 decimals)</label>
            <input id="solana-devnet-amount" inputMode="decimal" value={solanaAmount} onChange={(event) => setSolanaAmount(event.target.value.replace(/[^0-9.]/g, '').replace(/(\..*)\./g, '$1'))}
              placeholder="0.01" className="min-h-11 w-full rounded-lg border border-slate-700 bg-slate-950 px-3 text-sm text-white outline-none focus:border-cyan-400" />
            <button className={primaryClass}
              disabled={!runtime.native || runtime.walletBusy || !/^[1-9A-HJ-NP-Za-km-z]{32,44}$/.test(solanaRecipient) || !/^(?:0|[1-9][0-9]*)(?:\.[0-9]{1,9})?$/.test(solanaAmount) || Number(solanaAmount) <= 0}
              onClick={() => { native?.sendSolanaDevnetTransfer(solanaRecipient.trim(), solanaAmount.trim()); setLastEvent("Devnet transfer opened · approve or reject in your wallet"); }}>
              Send SOL using connected wallet
            </button>
            <p className="text-[11px] leading-relaxed text-amber-200/80">This action requires internet and a wallet approval. It sends native SOL on Devnet only; it is separate from the offline local-unit wallet and does not send mainnet funds.</p>
          </div>
        )}
        <p className="text-[11px] leading-relaxed text-slate-500">Online integrations are off by default. Enabling this setting permits the explicitly requested wallet adapter and Devnet RPC flow only; it does not enable cloud messaging, analytics, telemetry, remote fonts, or automatic uploads.</p>
      </div>

      <div className="rounded-xl border border-slate-800 bg-slate-950/70 p-3">
        <div className="flex items-center gap-2 text-xs font-semibold uppercase tracking-wide text-slate-400"><Network className="h-4 w-4 text-cyan-400" /> Latest native event</div>
        <p className="mt-2 break-words text-xs leading-relaxed text-slate-300">{lastEvent}</p>
        <button className="mt-3 text-xs text-cyan-300 underline underline-offset-4" onClick={refresh}>Refresh device status</button>
      </div>
    </section>
  );
}

function SmartphoneIcon() {
  return <svg viewBox="0 0 24 24" aria-hidden="true" className="h-6 w-6 text-cyan-300" fill="none" stroke="currentColor" strokeWidth="1.8">
    <rect x="6" y="2.5" width="12" height="19" rx="2" /><path d="M10 18.5h4" />
  </svg>;
}
