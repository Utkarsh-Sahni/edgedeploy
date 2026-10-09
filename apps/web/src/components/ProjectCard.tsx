import Link from "next/link";
import { Card } from "@/components/ui";
import { timeAgo } from "@/lib/format";
import { FRAMEWORK_LABELS, type Project } from "@/types";

export function ProjectCard({ project }: { project: Project }) {
  return (
    <Link href={`/projects/${project.id}`} className="group">
      <Card className="h-full p-5 transition-colors group-hover:border-zinc-400 dark:group-hover:border-zinc-600">
        <div className="flex items-start justify-between gap-2">
          <h3 className="truncate font-medium">{project.name}</h3>
          <span className="shrink-0 rounded bg-zinc-100 px-1.5 py-0.5 text-[11px] text-zinc-600 dark:bg-zinc-800 dark:text-zinc-400">
            {FRAMEWORK_LABELS[project.framework]}
          </span>
        </div>
        <p className="mt-2 truncate font-mono text-xs text-zinc-500">{project.repository}</p>
        <p className="mt-4 text-xs text-zinc-500">
          <span className="font-mono">{project.branch}</span> · created {timeAgo(project.createdAt)}
        </p>
      </Card>
    </Link>
  );
}
