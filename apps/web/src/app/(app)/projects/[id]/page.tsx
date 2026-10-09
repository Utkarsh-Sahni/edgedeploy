"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useEffect, useState } from "react";
import { DeploymentTable } from "@/components/DeploymentTable";
import { ProjectSettings } from "@/components/ProjectSettings";
import { StatusBadge } from "@/components/StatusBadge";
import { Banner, Button, Card, EmptyState, ErrorState, Skeleton } from "@/components/ui";
import { useAsync } from "@/hooks/useAsync";
import { api, toApiError } from "@/lib/api";
import { shortSha } from "@/lib/format";
import { FRAMEWORK_LABELS, isSettled, type Deployment } from "@/types";

const REFRESH_MS = 4000;
const REFRESH_WINDOW_MS = 120_000;

export default function ProjectPage() {
  const { id } = useParams<{ id: string }>();
  const project = useAsync(() => api.projects.get(id), [id]);
  const deployments = useAsync(() => api.deployments.forProject(id), [id]);
  const [tab, setTab] = useState<"overview" | "settings">("overview");
  const [deploying, setDeploying] = useState(false);
  const [queued, setQueued] = useState<Deployment | null>(null);
  const [deployError, setDeployError] = useState<string | null>(null);
  const [refreshUntil, setRefreshUntil] = useState(() => Date.now() + REFRESH_WINDOW_MS);

  // Keep history fresh for a while after loading/deploying while anything is unsettled.
  const hasActive = deployments.status === "success" && deployments.data.some((d) => !isSettled(d.status));
  const { reload: reloadDeployments } = deployments;
  useEffect(() => {
    if (!hasActive) return;
    const timer = setInterval(() => {
      if (Date.now() < refreshUntil) void reloadDeployments();
    }, REFRESH_MS);
    return () => clearInterval(timer);
  }, [hasActive, refreshUntil, reloadDeployments]);

  async function deploy() {
    setDeploying(true);
    setDeployError(null);
    try {
      const deployment = await api.deployments.trigger(id);
      setQueued(deployment);
      setRefreshUntil(Date.now() + REFRESH_WINDOW_MS);
      void deployments.reload();
    } catch (e) {
      setDeployError(toApiError(e).message);
    } finally {
      setDeploying(false);
    }
  }

  if (project.status === "loading") return <Skeleton className="h-48" />;
  if (project.status === "error") {
    return project.error.status === 404 ? (
      <EmptyState title="Project not found" description="It may have been deleted." action={<Link href="/projects" className="text-sm underline">Back to projects</Link>} />
    ) : (
      <ErrorState message={project.error.message} onRetry={project.reload} />
    );
  }

  const p = project.data;
  const latest = deployments.status === "success" ? deployments.data[0] : undefined;
  const production = deployments.status === "success" ? deployments.data.find((d) => d.status === "RUNNING") : undefined;

  return (
    <div className="space-y-8">
      <div>
        <Link href="/projects" className="text-sm text-zinc-500 hover:text-zinc-900 dark:hover:text-white">
          ← Projects
        </Link>
        <div className="mt-2 flex flex-wrap items-end justify-between gap-4">
          <div>
            <h1 className="text-2xl font-semibold tracking-tight">{p.name}</h1>
            <a href={`https://github.com/${p.repository}`} target="_blank" rel="noreferrer" className="mt-1 inline-block font-mono text-sm text-zinc-500 hover:underline">
              {p.repository}
            </a>
          </div>
          <Button onClick={deploy} disabled={deploying}>
            {deploying ? "Queuing…" : "Deploy"}
          </Button>
        </div>
        <div className="mt-6 flex gap-6 border-b border-zinc-200 text-sm dark:border-zinc-800">
          {(["overview", "settings"] as const).map((t) => (
            <button
              key={t}
              onClick={() => setTab(t)}
              className={`-mb-px border-b-2 pb-2 capitalize ${tab === t ? "border-zinc-900 font-medium dark:border-white" : "border-transparent text-zinc-500"}`}
            >
              {t}
            </button>
          ))}
        </div>
      </div>

      {queued && (
        <Banner tone="success">
          Deployment #{queued.number} queued for <span className="font-mono">{shortSha(queued.commitSha)}</span> on {p.branch}.{" "}
          <Link href={`/deployments/${queued.id}`} className="font-medium underline">
            View deployment →
          </Link>
        </Banner>
      )}
      {deployError && <Banner>{deployError}</Banner>}

      {tab === "settings" ? (
        <ProjectSettings project={p} onSaved={project.setData} />
      ) : (
        <>
          <dl className="grid gap-4 sm:grid-cols-4">
            <Detail label="Status">{latest ? <StatusBadge status={latest.status} /> : <span className="text-sm">Never deployed</span>}</Detail>
            <Detail label="Branch">
              <span className="font-mono text-sm">{p.branch}</span>
            </Detail>
            <Detail label="Framework">
              <span className="text-sm">{FRAMEWORK_LABELS[p.framework]}</span>
            </Detail>
            <Detail label="Created">
              <span className="text-sm">{new Date(p.createdAt).toLocaleDateString(undefined, { dateStyle: "medium" })}</span>
            </Detail>
            <div className="sm:col-span-4">
              <Detail label="Production URL">
                {production?.deploymentUrl ? (
                  <a href={production.deploymentUrl} target="_blank" rel="noreferrer" className="truncate font-mono text-sm underline underline-offset-4">
                    {production.deploymentUrl}
                  </a>
                ) : (
                  <span className="text-sm text-zinc-500">Available after the first successful deployment</span>
                )}
              </Detail>
            </div>
          </dl>

          <section>
            <h2 className="mb-3 font-medium">Deployments</h2>
            <Card>
              {deployments.status === "loading" && <Skeleton className="m-4 h-24" />}
              {deployments.status === "error" && <p className="p-6 text-sm text-red-600">{deployments.error.message}</p>}
              {deployments.status === "success" &&
                (deployments.data.length === 0 ? (
                  <p className="p-8 text-center text-sm text-zinc-500">No deployments yet. Click Deploy to queue the first one.</p>
                ) : (
                  <DeploymentTable deployments={deployments.data} />
                ))}
            </Card>
          </section>
        </>
      )}
    </div>
  );
}

function Detail({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <Card className="p-5">
      <dt className="text-xs font-medium uppercase tracking-wide text-zinc-500">{label}</dt>
      <dd className="mt-2">{children}</dd>
    </Card>
  );
}
