import React from 'react';

type Props = { native: boolean; events: string[] };

export default function SystemInfo({ native, events }: Props) {
  return (
    <section className="h-full min-h-[220px] rounded-2xl border border-slate-800 bg-[#101318] p-4">
      <div className="flex items-center justify-between gap-3">
        <div>
          <h3 className="text-sm font-semibold text-white">Native protocol event log</h3>
          <p className="mt-1 text-xs text-slate-500">Events emitted by Android services and transport callbacks</p>
        </div>
        <span className="rounded-lg border border-slate-700 px-2 py-1 text-[10px] text-slate-400">{native ? 'LIVE DEVICE' : 'BROWSER'}</span>
      </div>
      {!native ? <p className="mt-4 text-xs text-amber-200">Connect this dashboard to the Android APK to view real device events.</p> :
        events.length === 0 ? <p className="mt-4 text-xs text-slate-500">No native events reported yet. Start the mesh to populate this log.</p> :
        <ol className="mt-3 max-h-64 space-y-2 overflow-y-auto">
          {events.slice().reverse().map((event, index) => <li key={index} className="border-l-2 border-cyan-700 pl-3 text-xs leading-5 text-slate-300">{event}</li>)}
        </ol>}
    </section>
  );
}
