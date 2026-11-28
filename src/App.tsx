import React, { useEffect, useState } from 'react';
import { Activity, Bluetooth, Code2, ShieldCheck, Smartphone, Wifi } from 'lucide-react';
import MeshSimulator, { type LivePeer } from './components/MeshSimulator';
import PhoneWorkspace from './components/PhoneWorkspace';
import SystemInfo from './components/SystemInfo';
import CodeExporter from './components/CodeExporter';

type ObservedRoute = { id: string; nodes: string[]; observedAt: number; observations: number };

type RuntimeSnapshot = {
  native: boolean;
  running: boolean;
  connectedPeers: LivePeer[];
  observedRoutes: ObservedRoute[];
  localNodeId: string | null;
  recentEvents: string[];
};

const emptyRuntime: RuntimeSnapshot = {
  native: false,
  running: false,
  connectedPeers: [],
  observedRoutes: [],
  localNodeId: null,
  recentEvents: [],
};

export default function App() {
  const [runtime, setRuntime] = useState(emptyRuntime);

  useEffect(() => {
    let active = true;
    const readNativeStatus = () => {
      try {
        const bridge = (window as unknown as { MeshNetNative?: { getRuntimeStatus: () => string } }).MeshNetNative;
        if (!bridge) {
          if (active) setRuntime(emptyRuntime);
          return;
        }
        const value = JSON.parse(bridge.getRuntimeStatus()) as Partial<RuntimeSnapshot>;
        if (active) setRuntime({
          native: value.native === true,
          running: value.running === true,
          connectedPeers: Array.isArray(value.connectedPeers) ? value.connectedPeers : [],
          observedRoutes: Array.isArray(value.observedRoutes) ? value.observedRoutes : [],
          localNodeId: typeof value.localNodeId === 'string' ? value.localNodeId : null,
          recentEvents: Array.isArray(value.recentEvents) ? value.recentEvents : [],
        });
      } catch {
        if (active) setRuntime(current => ({ ...current, recentEvents: [...current.recentEvents, 'Unable to read native runtime status'].slice(-30) }));
      }
    };
    readNativeStatus();
    const interval = window.setInterval(readNativeStatus, 1500);
    const onVisible = () => readNativeStatus();
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      active = false;
      window.clearInterval(interval);
      document.removeEventListener('visibilitychange', onVisible);
    };
  }, []);

  return (
    <div className="min-h-screen bg-[#0A0B0D] text-slate-200">
      <header className="border-b border-slate-800 bg-[#111318]">
        <div className="mx-auto flex max-w-[1700px] items-center justify-between gap-4 px-4 py-4 sm:px-6">
          <div className="flex items-center gap-3">
            <div className="flex h-10 w-10 items-center justify-center rounded-xl border border-cyan-500/30 bg-cyan-500/10"><Activity className="h-5 w-5 text-cyan-300" /></div>
            <div>
              <div className="flex flex-wrap items-center gap-2 text-lg font-bold tracking-wide text-white">MESH<span className="text-cyan-400">NET</span><span className="rounded border border-cyan-500/30 bg-cyan-500/10 px-2 py-0.5 text-[9px] font-semibold uppercase tracking-wider text-cyan-200">Offline-first</span></div>
              <p className="mt-0.5 text-xs text-slate-400">Live peer-to-peer device console</p>
            </div>
          </div>
          <div className={`hidden items-center gap-2 rounded-full border px-3 py-1.5 text-xs sm:flex ${runtime.native ? "border-emerald-500/25 bg-emerald-500/5 text-emerald-200" : "border-amber-500/25 bg-amber-500/5 text-amber-200"}`}><ShieldCheck className="h-3.5 w-3.5" /> {runtime.native ? "Android · local runtime" : "Browser preview · no radio access"}</div>
        </div>
      </header>

      <main className="mx-auto grid w-full max-w-[1700px] gap-5 px-4 py-5 sm:px-6 xl:grid-cols-[minmax(0,1.65fr)_minmax(340px,0.85fr)]">
        <div className="flex min-w-0 flex-col gap-5">
          <section className="rounded-2xl border border-slate-800 bg-[#11151B] p-5">
            <p className="text-xs font-semibold uppercase tracking-[0.18em] text-cyan-300">Native device telemetry</p>
            <h1 className="mt-2 text-xl font-semibold text-white sm:text-2xl">Observe the real mesh, not a sample network.</h1>
            <p className="mt-2 max-w-3xl text-sm leading-6 text-slate-400">The topology and event stream below are populated by Android transport callbacks. Only connected peers are drawn; discovered devices, fictional users and simulated links are not counted.</p>
            <div className="mt-4 flex flex-wrap gap-2 text-[11px] text-slate-300">
              <span className="inline-flex items-center gap-1.5 rounded-lg border border-slate-700 bg-slate-900 px-2.5 py-2"><Bluetooth className="h-3.5 w-3.5 text-cyan-300" /> BLE / GATT</span>
              <span className="inline-flex items-center gap-1.5 rounded-lg border border-slate-700 bg-slate-900 px-2.5 py-2"><Wifi className="h-3.5 w-3.5 text-cyan-300" /> Local Wi-Fi transports</span>
              <span className="inline-flex items-center gap-1.5 rounded-lg border border-slate-700 bg-slate-900 px-2.5 py-2"><Smartphone className="h-3.5 w-3.5 text-cyan-300" /> Android runtime</span>
            </div>
          </section>
          <MeshSimulator native={runtime.native} running={runtime.running} peers={runtime.connectedPeers} routes={runtime.observedRoutes} localNodeId={runtime.localNodeId} />
          <SystemInfo native={runtime.native} events={runtime.recentEvents} />
          <section className="rounded-2xl border border-slate-800 bg-[#11151B] p-4 sm:p-5">
            <div className="mb-3 flex items-center gap-2"><Code2 className="h-4 w-4 text-cyan-300" /><h2 className="text-sm font-semibold text-white">Application source code</h2></div>
            <CodeExporter />
          </section>
        </div>
        <aside className="min-w-0">
          <div className="mb-3 text-center">
            <span className="text-[10px] font-bold uppercase tracking-widest text-slate-500">Native runtime</span>
            <h2 className="mt-1 text-xs font-semibold uppercase tracking-wide text-slate-300">Device controls and services</h2>
          </div>
          <PhoneWorkspace />
        </aside>
      </main>
    </div>
  );
}
