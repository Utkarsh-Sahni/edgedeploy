import type { DeploymentStatus } from "@/types";

const STYLES: Record<DeploymentStatus, { label: string; className: string; pulse?: boolean }> = {
  QUEUED: { label: "Queued", className: "bg-zinc-100 text-zinc-700 ring-zinc-300 dark:bg-zinc-800 dark:text-zinc-300 dark:ring-zinc-700" },
  BUILDING: { label: "Building", className: "bg-amber-50 text-amber-800 ring-amber-300 dark:bg-amber-950 dark:text-amber-300 dark:ring-amber-800", pulse: true },
  PUSHING: { label: "Pushing", className: "bg-amber-50 text-amber-800 ring-amber-300 dark:bg-amber-950 dark:text-amber-300 dark:ring-amber-800", pulse: true },
  DEPLOYING: { label: "Deploying", className: "bg-sky-50 text-sky-800 ring-sky-300 dark:bg-sky-950 dark:text-sky-300 dark:ring-sky-800", pulse: true },
  HEALTH_CHECK: { label: "Health check", className: "bg-sky-50 text-sky-800 ring-sky-300 dark:bg-sky-950 dark:text-sky-300 dark:ring-sky-800", pulse: true },
  IMAGE_BUILT: { label: "Image built", className: "bg-violet-50 text-violet-800 ring-violet-300 dark:bg-violet-950 dark:text-violet-300 dark:ring-violet-800" },
  RUNNING: { label: "Ready", className: "bg-emerald-50 text-emerald-800 ring-emerald-300 dark:bg-emerald-950 dark:text-emerald-300 dark:ring-emerald-800" },
  FAILED: { label: "Failed", className: "bg-red-50 text-red-800 ring-red-300 dark:bg-red-950 dark:text-red-300 dark:ring-red-800" },
  STOPPED: { label: "Stopped", className: "bg-zinc-100 text-zinc-500 ring-zinc-300 dark:bg-zinc-800 dark:text-zinc-400 dark:ring-zinc-700" },
};

export function StatusBadge({ status }: { status: DeploymentStatus }) {
  const style = STYLES[status];
  return (
    <span className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-xs font-medium ring-1 ring-inset ${style.className}`}>
      <span className="relative flex h-1.5 w-1.5">
        {style.pulse && <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-current opacity-60" />}
        <span className="relative inline-flex h-1.5 w-1.5 rounded-full bg-current" />
      </span>
      {style.label}
    </span>
  );
}
