"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { Shell } from "@/components/Shell";
import { api, statusTone, type Deployment, type Project } from "@/lib/api";

export default function ProjectPage() {
  const params = useParams<{ id: string }>();
  const [project, setProject] = useState<Project | null>(null);
  const [deployments, setDeployments] = useState<Deployment[]>([]);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    const [nextProject, nextDeployments] = await Promise.all([
      api.project(params.id),
      api.deployments(params.id),
    ]);
    setProject(nextProject);
    setDeployments(nextDeployments);
  }, [params.id]);

  useEffect(() => {
    load().catch(() => undefined);
  }, [load]);

  async function deploy() {
    setBusy(true);
    try {
      await api.deploy(params.id);
      await load();
    } finally {
      setBusy(false);
    }
  }

  if (!project) {
    return (
      <Shell>
        <p className="text-zinc-400">Loading project…</p>
      </Shell>
    );
  }

  return (
    <Shell>
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 className="text-3xl font-semibold">{project.name}</h1>
          <p className="mt-1 font-mono text-sm text-zinc-400">
            {project.repository} · {project.branch} · {project.framework || "auto"}
          </p>
        </div>
        <div className="flex gap-3">
          <Link href={`/projects/${project.id}/env`} className="rounded-full border border-white/15 px-4 py-2 text-sm">
            Environment
          </Link>
          <button
            onClick={deploy}
            disabled={busy}
            className="rounded-full bg-accent px-4 py-2 text-sm font-semibold text-ink-950 disabled:opacity-60"
          >
            {busy ? "Queueing…" : "Deploy"}
          </button>
        </div>
      </div>

      <div className="mt-8 grid gap-3">
        {deployments.length === 0 && <div className="panel p-6 text-zinc-400">No deployments yet.</div>}
        {deployments.map((deployment) => (
          <Link
            key={deployment.id}
            href={`/projects/${project.id}/deployments/${deployment.id}`}
            className="panel flex items-center justify-between p-5 hover:border-accent/40"
          >
            <div>
              <p className="font-mono text-sm">{deployment.commitSha.slice(0, 12)}</p>
              {deployment.deploymentUrl && <p className="mt-1 text-sm text-accent">{deployment.deploymentUrl}</p>}
            </div>
            <span className={`rounded-full border px-3 py-1 text-xs ${statusTone(deployment.status)}`}>
              {deployment.status}
            </span>
          </Link>
        ))}
      </div>
    </Shell>
  );
}
