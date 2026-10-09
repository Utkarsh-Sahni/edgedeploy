"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { DeploymentProgress } from "@/components/DeploymentProgress";
import { DeploymentTimeline } from "@/components/DeploymentTimeline";
import { LogViewer } from "@/components/LogViewer";
import { StatusBadge } from "@/components/StatusBadge";
import { Banner, Button, Card, EmptyState, ErrorState, Skeleton } from "@/components/ui";
import { useAsync } from "@/hooks/useAsync";
import { useDeploymentEvents } from "@/hooks/useDeploymentEvents";
import { useDeploymentLogs } from "@/hooks/useDeploymentLogs";
import { api, toApiError } from "@/lib/api";
import { duration, shortSha } from "@/lib/format";
import { canRedeploy, isCancellable, isSettled, type DeploymentLog, type DeploymentStatus, type DeploymentStep } from "@/types";

export default function DeploymentPage() {
  const { id } = useParams<{ id: string }>();
  const router = useRouter();
  // REST is the source of truth for status; status SSE events only say *when* to re-read it.
  const view = useAsync(() => api.deployments.get(id), [id]);
  const [now, setNow] = useState(() => Date.now());
  const [busy, setBusy] = useState<"cancel" | "redeploy" | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  const settled = view.status === "success" ? isSettled(view.data.status) : true;
  const { reload } = view;
  const onStatusChange = useCallback(() => void reload(), [reload]);
  const live = useDeploymentEvents(id, view.status === "success" && !settled, onStatusChange);
  const logs = useDeploymentLogs(id, view.status !== "success" || settled);

  // Tick the duration counter while the deployment is in flight.
  useEffect(() => {
    if (settled) return;
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, [settled]);

  async function cancel() {
    setBusy("cancel");
    setActionError(null);
    try {
      await api.deployments.cancel(id);
      await reload();
    } catch (e) {
      setActionError(toApiError(e).message);
    } finally {
      setBusy(null);
    }
  }

  async function redeploy() {
    if (view.status !== "success") return;
    setBusy("redeploy");
    setActionError(null);
    try {
      // Same commit as this deployment: a redeploy reproduces it exactly.
      const next = await api.deployments.trigger(view.data.projectId, view.data.commitSha ?? undefined);
      router.push(`/deployments/${next.id}`);
    } catch (e) {
      setActionError(toApiError(e).message);
      setBusy(null);
    }
  }

  if (view.status === "loading") return <Skeleton className="h-64" />;
  if (view.status === "error") {
    return view.error.status === 404 ? (
      <EmptyState title="Deployment not found" action={<Link href="/dashboard" className="text-sm underline">Back to dashboard</Link>} />
    ) : (
      <ErrorState message={view.error.message} onRetry={reload} />
    );
  }

  const deployment = view.data;
  const failedAt = deployment.status === "FAILED" ? failedStepLabel(logs.lines) : null;

  return (
    <div className="space-y-8">
      <div>
        <Link href={`/projects/${deployment.projectId}`} className="text-sm text-zinc-500 hover:text-zinc-900 dark:hover:text-white">
          ← {deployment.projectName}
        </Link>
        <div className="mt-2 flex flex-wrap items-center gap-3">
          <h1 className="text-2xl font-semibold tracking-tight">Deployment #{deployment.number}</h1>
          <StatusBadge status={deployment.status} />
          {live && <span className="text-xs text-emerald-600 dark:text-emerald-400">● live</span>}
          <div className="ml-auto flex gap-2">
            <a href="#logs" className="inline-flex items-center rounded-lg px-3 py-2 text-sm text-zinc-600 hover:text-zinc-900 dark:text-zinc-400 dark:hover:text-white">
              View logs
            </a>
            {deployment.status === "RUNNING" && deployment.deploymentUrl && (
              <a href={deployment.deploymentUrl} target="_blank" rel="noreferrer"
                className="inline-flex items-center rounded-lg bg-emerald-600 px-3 py-2 text-sm font-medium text-white hover:bg-emerald-500">
                Open Application ↗
              </a>
            )}
            {isCancellable(deployment.status) && (
              <Button variant="secondary" onClick={cancel} disabled={busy !== null}>
                {busy === "cancel" ? "Cancelling…" : "Cancel"}
              </Button>
            )}
            {canRedeploy(deployment.status) && (
              <Button onClick={redeploy} disabled={busy !== null}>
                {busy === "redeploy" ? "Queuing…" : "Redeploy"}
              </Button>
            )}
          </div>
        </div>
      </div>

      {actionError && <Banner>{actionError}</Banner>}
      {deployment.status === "FAILED" && (
        <Banner>
          <span className="font-medium">Deployment failed{failedAt ? ` during ${failedAt}` : ""}.</span>{" "}
          {deployment.errorMessage ?? "See the logs below for details."}
        </Banner>
      )}
      {deployment.status === "STOPPED" && deployment.errorMessage && <Banner tone="info">{deployment.errorMessage}</Banner>}
      {deployment.status === "RUNNING" && deployment.deploymentUrl && (
        <Banner tone="success">
          Live at{" "}
          <a href={deployment.deploymentUrl} target="_blank" rel="noreferrer" className="break-all font-mono underline underline-offset-4">
            {deployment.deploymentUrl}
          </a>
        </Banner>
      )}
      {deployment.status === "IMAGE_BUILT" && (
        <Banner tone="info">
          Image built: <span className="break-all font-mono">{deployment.imageUri}</span>. No deployment target is configured
          (local mode); set EDGEDEPLOY_AWS_ENABLED=true on the worker to deploy to ECS Fargate.
        </Banner>
      )}

      <Card className="p-6">
        <DeploymentProgress status={deployment.status} reached={reachedStatus(logs.lines)} />
        <dl className="mt-6 grid gap-4 text-sm sm:grid-cols-4">
          <Detail label="Commit" value={<span className="font-mono" title={deployment.commitSha ?? undefined}>{shortSha(deployment.commitSha)}</span>} />
          <Detail label="Duration" value={duration(deployment.startedAt, deployment.completedAt, now)} />
          <Detail label="Deployment ID" value={<span className="break-all font-mono text-xs">{deployment.id}</span>} />
          <Detail label="Image" value={deployment.imageUri ? <span className="break-all font-mono text-xs">{deployment.imageUri}</span> : "—"} />
        </dl>
      </Card>

      <div className="grid gap-8 lg:grid-cols-[minmax(0,20rem)_minmax(0,1fr)]">
        <section>
          <h2 className="mb-3 font-medium">Timeline</h2>
          <Card className="p-5">
            <DeploymentTimeline deployment={deployment} logs={logs.lines} />
          </Card>
        </section>
        <section id="logs" className="scroll-mt-20">
          <h2 className="mb-3 font-medium">Deployment logs</h2>
          {logs.error && <div className="mb-3"><Banner>{logs.error}</Banner></div>}
          <LogViewer lines={logs.lines} live={logs.live} />
        </section>
      </div>
    </div>
  );
}

/** Which status a deployment was in when a step failed or completed last (for the progress bar of failed deployments). */
const STEP_STATUS: Record<DeploymentStep, DeploymentStatus> = {
  QUEUED: "QUEUED",
  CLONE: "BUILDING",
  COMMIT: "BUILDING",
  FRAMEWORK: "BUILDING",
  IMAGE: "BUILDING",
  PUSH: "PUSHING",
  DEPLOY: "DEPLOYING",
  HEALTH: "HEALTH_CHECK",
  LIVE: "RUNNING",
};

const STEP_NAMES: Partial<Record<DeploymentStep, string>> = {
  CLONE: "clone",
  COMMIT: "checkout",
  FRAMEWORK: "framework detection",
  IMAGE: "the Docker build",
  PUSH: "the image push",
  DEPLOY: "the ECS rollout",
  HEALTH: "the health check",
};

function reachedStatus(lines: DeploymentLog[]): DeploymentStatus | undefined {
  const withStep = lines.filter((line) => line.step);
  const last = withStep[withStep.length - 1];
  if (!last?.step) return undefined;
  if (last.level === "ERROR") return STEP_STATUS[last.step];
  // The step after the last completed one is where it stopped.
  if (last.step === "IMAGE") return "PUSHING";
  if (last.step === "PUSH") return "DEPLOYING";
  if (last.step === "DEPLOY") return "HEALTH_CHECK";
  return STEP_STATUS[last.step];
}

function failedStepLabel(lines: DeploymentLog[]): string | null {
  const failed = [...lines].reverse().find((line) => line.step && line.level === "ERROR");
  return failed?.step ? STEP_NAMES[failed.step] ?? null : null;
}

function Detail({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div>
      <dt className="text-xs font-medium uppercase tracking-wide text-zinc-500">{label}</dt>
      <dd className="mt-1">{value}</dd>
    </div>
  );
}
