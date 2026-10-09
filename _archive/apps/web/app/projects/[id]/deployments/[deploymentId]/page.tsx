"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useEffect, useState } from "react";
import { Shell } from "@/components/Shell";
import { api, statusTone, type Deployment, type DeploymentLog } from "@/lib/api";

export default function DeploymentDetailPage() {
  const params = useParams<{ id: string; deploymentId: string }>();
  const [deployment, setDeployment] = useState<Deployment | null>(null);
  const [logs, setLogs] = useState<DeploymentLog[]>([]);

  useEffect(() => {
    let timer: ReturnType<typeof setInterval>;
    async function load() {
      const [nextDeployment, nextLogs] = await Promise.all([
        api.deployment(params.deploymentId),
        api.logs(params.deploymentId),
      ]);
      setDeployment(nextDeployment);
      setLogs(nextLogs);
    }
    load().catch(() => undefined);
    timer = setInterval(() => load().catch(() => undefined), 2500);
    return () => clearInterval(timer);
  }, [params.deploymentId]);

  if (!deployment) {
    return (
      <Shell>
        <p className="text-zinc-400">Loading deployment…</p>
      </Shell>
    );
  }

  return (
    <Shell>
      <Link href={`/projects/${params.id}`} className="text-sm text-zinc-400 hover:text-white">
        ← Back to project
      </Link>
      <div className="mt-4 flex flex-wrap items-center justify-between gap-4">
        <div>
          <h1 className="text-3xl font-semibold">Deployment</h1>
          <p className="mt-1 font-mono text-sm text-zinc-400">{deployment.commitSha}</p>
        </div>
        <div className="flex items-center gap-3">
          <span className={`rounded-full border px-3 py-1 text-xs ${statusTone(deployment.status)}`}>
            {deployment.status}
          </span>
          {deployment.status === "RUNNING" && (
            <button
              onClick={() => api.stop(deployment.id).then(setDeployment)}
              className="rounded-full border border-white/15 px-3 py-1 text-sm"
            >
              Stop
            </button>
          )}
        </div>
      </div>
      {deployment.deploymentUrl && (
        <a className="mt-4 inline-block text-accent" href={deployment.deploymentUrl} target="_blank" rel="noreferrer">
          {deployment.deploymentUrl}
        </a>
      )}
      {deployment.errorMessage && <p className="mt-3 text-rose-300">{deployment.errorMessage}</p>}

      <pre className="panel mt-6 max-h-[520px] overflow-auto p-4 font-mono text-xs leading-6 text-zinc-300">
        {logs.length === 0
          ? "Waiting for worker logs…"
          : logs.map((log) => `${log.timestamp} [${log.level}] ${log.message}`).join("\n")}
      </pre>
    </Shell>
  );
}
