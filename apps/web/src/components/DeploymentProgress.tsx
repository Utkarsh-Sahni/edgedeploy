import type { DeploymentStatus } from "@/types";

const DEPLOY_STEPS: { status: DeploymentStatus; label: string }[] = [
  { status: "QUEUED", label: "Queued" },
  { status: "BUILDING", label: "Building" },
  { status: "PUSHING", label: "Pushing image" },
  { status: "DEPLOYING", label: "Deploying" },
  { status: "HEALTH_CHECK", label: "Health check" },
  { status: "RUNNING", label: "Running" },
];

/** Local mode (no deployment target configured): builds end with the image. */
const LOCAL_STEPS: { status: DeploymentStatus; label: string }[] = [
  { status: "QUEUED", label: "Queued" },
  { status: "BUILDING", label: "Building" },
  { status: "IMAGE_BUILT", label: "Image built" },
];

/** Horizontal stepper driven purely by the deployment status. */
export function DeploymentProgress({ status, reached }: { status: DeploymentStatus; reached?: DeploymentStatus }) {
  const steps = status === "IMAGE_BUILT" || reached === "IMAGE_BUILT" ? LOCAL_STEPS : DEPLOY_STEPS;
  const failed = status === "FAILED" || status === "STOPPED";
  // For failed deployments, highlight how far they got (reached = last status before failing, if known).
  const position = steps.findIndex((s) => s.status === (failed ? reached : status));
  const finished = status === "RUNNING" || status === "IMAGE_BUILT";

  return (
    <ol className={`grid gap-2 ${steps.length === 3 ? "grid-cols-3" : "grid-cols-6"}`} aria-label="Deployment progress">
      {steps.map((step, index) => {
        const done = finished || (position >= 0 && index < position);
        const active = !failed && !finished && position === index;
        const broken = failed && position === index;
        const bar = broken
          ? "bg-red-500"
          : done
            ? "bg-emerald-500"
            : active
              ? "animate-pulse bg-sky-500"
              : "bg-zinc-200 dark:bg-zinc-800";
        return (
          <li key={step.status} className="space-y-2">
            <div className={`h-1.5 rounded-full ${bar}`} />
            <p className={`text-xs ${done || active || broken ? "font-medium" : "text-zinc-500"} ${broken ? "text-red-600 dark:text-red-400" : ""}`}>
              {step.label}
            </p>
          </li>
        );
      })}
    </ol>
  );
}
