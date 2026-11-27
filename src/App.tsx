import { Activity, Bluetooth, CheckCircle2, Code2, ShieldCheck, Smartphone, Wifi } from 'lucide-react';
import PhoneWorkspace from './components/PhoneWorkspace';

const sourceAreas = [
  {
    title: 'Dashboard UI',
    path: 'src/',
    detail: 'React/Vite interface compiled locally and bundled into the Android APK.',
  },
  {
    title: 'Android runtime',
    path: 'mobile/app/src/main/',
    detail: 'Native Kotlin services, BLE/GATT, Wi-Fi transports, storage, files, voice and wallet bridge.',
  },
  {
    title: 'Android protocol tests',
    path: 'mobile/app/src/androidTest/',
    detail: 'Instrumentation tests for protocol and persistent-storage behavior.',
  },
];

export default function App() {
  return (
    <div className="min-h-screen bg-[#0A0B0D] text-slate-200">
      <header className="border-b border-slate-800 bg-[#111318]">
        <div className="mx-auto flex max-w-6xl items-center justify-between gap-4 px-4 py-4 sm:px-6">
          <div className="flex min-w-0 items-center gap-3">
            <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl border border-cyan-500/30 bg-cyan-500/10">
              <Activity className="h-5 w-5 text-cyan-300" />
            </div>
            <div className="min-w-0">
              <div className="flex flex-wrap items-center gap-2 text-lg font-bold tracking-wide text-white">
                MESH<span className="text-cyan-400">NET</span>
                <span className="rounded border border-cyan-500/30 bg-cyan-500/10 px-2 py-0.5 text-[9px] font-semibold uppercase tracking-wider text-cyan-200">
                  Offline-first
                </span>
              </div>
              <p className="mt-0.5 text-xs text-slate-400">Native peer-to-peer device console</p>
            </div>
          </div>
          <div className="hidden items-center gap-2 rounded-full border border-emerald-500/25 bg-emerald-500/5 px-3 py-1.5 text-xs text-emerald-200 sm:flex">
            <ShieldCheck className="h-3.5 w-3.5" />
            Local-first mode
          </div>
        </div>
      </header>

      <main className="mx-auto flex w-full max-w-6xl flex-col gap-6 px-4 py-6 sm:px-6 sm:py-8">
        <section className="grid gap-4 rounded-2xl border border-slate-800 bg-[#11151B] p-5 sm:grid-cols-[1fr_auto] sm:items-center sm:p-6">
          <div>
            <p className="text-xs font-semibold uppercase tracking-[0.18em] text-cyan-300">Actual device controls</p>
            <h1 className="mt-2 text-xl font-semibold text-white sm:text-2xl">Connect devices. Exchange data locally.</h1>
            <p className="mt-2 max-w-2xl text-sm leading-6 text-slate-400">
              This screen controls the Android runtime through the app's local JavaScript bridge.
              It does not show fabricated peers, simulated radio links, or sample blockchain events.
            </p>
          </div>
          <div className="flex flex-wrap gap-2 text-[11px] text-slate-300">
            <span className="inline-flex items-center gap-1.5 rounded-lg border border-slate-700 bg-slate-900 px-2.5 py-2"><Bluetooth className="h-3.5 w-3.5 text-cyan-300" /> BLE / GATT</span>
            <span className="inline-flex items-center gap-1.5 rounded-lg border border-slate-700 bg-slate-900 px-2.5 py-2"><Wifi className="h-3.5 w-3.5 text-cyan-300" /> Local Wi-Fi</span>
            <span className="inline-flex items-center gap-1.5 rounded-lg border border-slate-700 bg-slate-900 px-2.5 py-2"><Smartphone className="h-3.5 w-3.5 text-cyan-300" /> Android</span>
          </div>
        </section>

        <section aria-labelledby="runtime-heading" className="space-y-3">
          <div>
            <h2 id="runtime-heading" className="text-sm font-semibold uppercase tracking-wider text-slate-300">Device runtime</h2>
            <p className="mt-1 text-xs text-slate-500">Real device status is reported by Android. Browser preview cannot operate phone radios.</p>
          </div>
          <div className="mx-auto w-full max-w-3xl">
            <PhoneWorkspace />
          </div>
        </section>

        <section aria-labelledby="source-heading" className="rounded-2xl border border-slate-800 bg-[#11151B] p-5 sm:p-6">
          <div className="flex items-center gap-2">
            <Code2 className="h-4 w-4 text-cyan-300" />
            <h2 id="source-heading" className="text-sm font-semibold text-white">Source included in this project</h2>
          </div>
          <p className="mt-2 max-w-3xl text-xs leading-5 text-slate-400">
            The dashboard is built from the repository's source and packaged inside the APK.
            Android functionality is implemented in native Kotlin source and compiled into the same APK.
            The installed app does not load its dashboard or application code from a hosted website.
          </p>
          <ul className="mt-4 grid gap-3 sm:grid-cols-3">
            {sourceAreas.map((area) => (
              <li key={area.path} className="rounded-xl border border-slate-800 bg-[#0A0B0D] p-3">
                <div className="flex items-center gap-2 text-xs font-semibold text-slate-200">
                  <CheckCircle2 className="h-3.5 w-3.5 shrink-0 text-emerald-300" />
                  {area.title}
                </div>
                <code className="mt-2 block break-all text-[11px] text-cyan-200">{area.path}</code>
                <p className="mt-2 text-[11px] leading-5 text-slate-500">{area.detail}</p>
              </li>
            ))}
          </ul>
          <p className="mt-4 border-t border-slate-800 pt-3 text-[11px] leading-5 text-amber-200/80">
            External network use is not part of local messaging. The optional Solana Devnet wallet flow is separate,
            disabled by default, and requires an explicit user action and internet access. Cryptographic production
            hardening and real-device interoperability still require separate validation.
          </p>
        </section>
      </main>
    </div>
  );
}
