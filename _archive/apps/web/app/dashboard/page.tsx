"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { Shell } from "@/components/Shell";
import { api, statusTone, type Project } from "@/lib/api";

export default function DashboardPage() {
  const [projects, setProjects] = useState<Project[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.projects().then(setProjects).catch((err) => setError(err.message));
  }, []);

  return (
    <Shell>
      <div className="mb-6 flex items-end justify-between">
        <div>
          <h1 className="text-3xl font-semibold tracking-tight">Projects</h1>
          <p className="mt-1 text-zinc-400">Repositories connected to EdgeDeploy.</p>
        </div>
      </div>
      {error && <p className="mb-4 text-rose-300">{error}</p>}
      <div className="grid gap-4">
        {projects.length === 0 && (
          <div className="panel p-8 text-zinc-400">
            No projects yet. Connect a GitHub repository to create your first deployment pipeline.
          </div>
        )}
        {projects.map((project) => (
          <Link key={project.id} href={`/projects/${project.id}`} className="panel p-5 transition hover:border-accent/40">
            <div className="flex items-center justify-between gap-4">
              <div>
                <h2 className="text-lg font-medium">{project.name}</h2>
                <p className="mt-1 font-mono text-sm text-zinc-400">
                  {project.repository} · {project.branch}
                </p>
              </div>
              <span className={`rounded-full border px-3 py-1 text-xs ${statusTone(project.latestStatus)}`}>
                {project.latestStatus || "NO DEPLOYS"}
              </span>
            </div>
            {project.latestUrl && <p className="mt-3 text-sm text-accent">{project.latestUrl}</p>}
          </Link>
        ))}
      </div>
    </Shell>
  );
}
