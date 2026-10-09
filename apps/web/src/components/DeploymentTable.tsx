import Link from "next/link";
import { StatusBadge } from "@/components/StatusBadge";
import { duration, shortSha, timeAgo } from "@/lib/format";
import type { Deployment } from "@/types";

/** Compact deployment history; optionally shows which project each row belongs to. */
export function DeploymentTable({ deployments, showProject = false }: { deployments: Deployment[]; showProject?: boolean }) {
  return (
    <ul className="divide-y divide-zinc-200 dark:divide-zinc-800">
      {deployments.map((d) => (
        <li key={d.id}>
          <Link href={`/deployments/${d.id}`} className="flex items-center gap-4 px-5 py-3 text-sm hover:bg-zinc-50 dark:hover:bg-zinc-800/50">
            <span className="w-14 shrink-0 font-mono text-xs text-zinc-500">#{d.number}</span>
            {showProject && <span className="w-40 shrink-0 truncate font-medium">{d.projectName}</span>}
            <span className="w-32 shrink-0">
              <StatusBadge status={d.status} />
            </span>
            <span className="hidden font-mono text-xs text-zinc-500 sm:inline">{shortSha(d.commitSha)}</span>
            <span className="ml-auto text-xs text-zinc-500">{duration(d.startedAt, d.completedAt)}</span>
            <span className="w-28 text-right text-xs text-zinc-500">{timeAgo(d.createdAt)}</span>
          </Link>
        </li>
      ))}
    </ul>
  );
}
