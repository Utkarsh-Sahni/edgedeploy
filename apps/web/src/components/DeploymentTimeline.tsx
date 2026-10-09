import { clockTime } from "@/lib/format";
import { DELIVERY_STEPS, DEPLOYMENT_STEPS, isInProgress, type Deployment, type DeploymentLog, type DeploymentStep } from "@/types";

const LABELS: Record<DeploymentStep, { done: string; failed: string; active: string }> = {
  QUEUED: { done: "Deployment queued", failed: "Deployment could not be queued", active: "Queuing deployment" },
  CLONE: { done: "Repository cloned", failed: "Repository clone failed", active: "Cloning repository" },
  COMMIT: { done: "Commit verified", failed: "Commit verification failed", active: "Verifying commit" },
  FRAMEWORK: { done: "Framework detected", failed: "Framework detection failed", active: "Detecting framework" },
  IMAGE: { done: "Docker image built", failed: "Docker build failed", active: "Building Docker image" },
  PUSH: { done: "Image pushed to ECR", failed: "Image push failed", active: "Pushing image to ECR" },
  DEPLOY: { done: "Deployed to ECS", failed: "ECS deployment failed", active: "Deploying to ECS" },
  HEALTH: { done: "Health check passed", failed: "Health check failed", active: "Running health check" },
  LIVE: { done: "Deployment successful", failed: "Deployment failed", active: "Going live" },
};

type StepState = "done" | "failed" | "active" | "skipped";

/**
 * The deployment's milestones, derived from step-tagged log lines (the source of truth), so the
 * timeline and the raw log can never disagree.
 */
export function DeploymentTimeline({ deployment, logs }: { deployment: Deployment; logs: DeploymentLog[] }) {
  const byStep = new Map<DeploymentStep, DeploymentLog>();
  for (const line of logs) {
    if (line.step) byStep.set(line.step, line); // later lines (e.g. an ERROR) win
  }
  const working = isInProgress(deployment.status);
  // Local builds (no deployment target) end with the image: don't show push/deploy steps for them.
  const local = deployment.status === "IMAGE_BUILT" || (!working && !DELIVERY_STEPS.some((step) => byStep.has(step))
    && deployment.status !== "RUNNING");
  let blocked = false;

  const steps = DEPLOYMENT_STEPS.filter((step) => !(local && DELIVERY_STEPS.includes(step))).map((step) => {
    const line = byStep.get(step);
    let state: StepState;
    if (line?.level === "ERROR") {
      state = "failed";
      blocked = true;
    } else if (line) {
      state = "done";
    } else if (blocked || !working) {
      state = "skipped";
    } else {
      state = "active";
      blocked = true; // only the first unfinished step is in progress
    }
    return { step, state, line };
  });

  return (
    <ol className="space-y-0">
      {steps.map(({ step, state, line }, index) => (
        <li key={step} className="relative flex gap-4 pb-6 last:pb-0">
          {index < steps.length - 1 && (
            <span aria-hidden className="absolute left-[11px] top-7 h-[calc(100%-1.5rem)] w-px bg-zinc-200 dark:bg-zinc-800" />
          )}
          <StepIcon state={state} />
          <div className="min-w-0 flex-1 pt-0.5">
            <div className="flex flex-wrap items-baseline justify-between gap-2">
              <p className={`text-sm font-medium ${state === "failed" ? "text-red-700 dark:text-red-400" : state === "skipped" ? "text-zinc-400" : ""}`}>
                {state === "failed" ? LABELS[step].failed : state === "active" ? `${LABELS[step].active}…` : state === "skipped" ? LABELS[step].active : LABELS[step].done}
              </p>
              {line && <span className="font-mono text-xs text-zinc-400">{clockTime(line.timestamp)}</span>}
            </div>
            {line && state !== "skipped" && (
              <p className={`mt-0.5 break-words text-xs ${state === "failed" ? "text-red-600 dark:text-red-300" : "text-zinc-500"}`}>{line.message}</p>
            )}
          </div>
        </li>
      ))}
    </ol>
  );
}

function StepIcon({ state }: { state: StepState }) {
  const base = "relative z-[1] grid h-6 w-6 shrink-0 place-items-center rounded-full text-xs font-semibold";
  switch (state) {
    case "done":
      return <span className={`${base} bg-emerald-500 text-white`}>✓</span>;
    case "failed":
      return <span className={`${base} bg-red-600 text-white`}>✗</span>;
    case "active":
      return (
        <span className={`${base} bg-sky-100 dark:bg-sky-950`}>
          <span className="h-3 w-3 animate-spin rounded-full border-2 border-sky-300 border-t-sky-600" />
        </span>
      );
    default:
      return <span className={`${base} border border-zinc-300 bg-white dark:border-zinc-700 dark:bg-zinc-900`} />;
  }
}
