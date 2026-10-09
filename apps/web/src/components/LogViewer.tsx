"use client";

import { useEffect, useRef, useState } from "react";
import { clockTime } from "@/lib/format";
import type { DeploymentLog } from "@/types";

const LEVEL_STYLES: Record<DeploymentLog["level"], string> = {
  DEBUG: "text-zinc-500",
  INFO: "text-zinc-200",
  WARN: "text-amber-300",
  ERROR: "text-red-400",
};

/** Terminal-style log view that follows new output unless the user scrolls up. */
export function LogViewer({ lines, live }: { lines: DeploymentLog[]; live: boolean }) {
  const container = useRef<HTMLDivElement>(null);
  const [follow, setFollow] = useState(true);

  useEffect(() => {
    if (follow && container.current) {
      container.current.scrollTop = container.current.scrollHeight;
    }
  }, [lines, follow]);

  function onScroll() {
    const el = container.current;
    if (el) setFollow(el.scrollHeight - el.scrollTop - el.clientHeight < 40);
  }

  return (
    <div className="overflow-hidden rounded-xl border border-zinc-800 bg-zinc-950">
      <div className="flex items-center justify-between border-b border-zinc-800 px-4 py-2 text-xs text-zinc-400">
        <span>
          {lines.length} {lines.length === 1 ? "line" : "lines"}
        </span>
        <span className="flex items-center gap-3">
          {live && <span className="text-emerald-400">● streaming</span>}
          {!follow && (
            <button onClick={() => setFollow(true)} className="text-zinc-300 underline underline-offset-2">
              Jump to latest
            </button>
          )}
        </span>
      </div>
      <div ref={container} onScroll={onScroll} className="max-h-[32rem] overflow-y-auto px-4 py-3 font-mono text-xs leading-5">
        {lines.length === 0 ? (
          <p className="text-zinc-500">Waiting for logs…</p>
        ) : (
          lines.map((line) => (
            <div key={line.seq} className="flex gap-3 whitespace-pre-wrap break-all">
              <span className="shrink-0 select-none text-zinc-600">{clockTime(line.timestamp)}</span>
              <span className={line.step ? `font-semibold ${LEVEL_STYLES[line.level]}` : LEVEL_STYLES[line.level]}>{line.message}</span>
            </div>
          ))
        )}
      </div>
    </div>
  );
}
