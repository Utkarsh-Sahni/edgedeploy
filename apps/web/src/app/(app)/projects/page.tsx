"use client";

import { ProjectCard } from "@/components/ProjectCard";
import { ButtonLink, EmptyState, ErrorState, Skeleton } from "@/components/ui";
import { useAsync } from "@/hooks/useAsync";
import { api } from "@/lib/api";

export default function ProjectsPage() {
  const projects = useAsync(() => api.projects.list(), []);

  return (
    <div className="space-y-8">
      <div className="flex items-end justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">Projects</h1>
          <p className="mt-1 text-sm text-zinc-500">Each project deploys one branch of a GitHub repository.</p>
        </div>
        <ButtonLink href="/projects/new">New project</ButtonLink>
      </div>

      {projects.status === "loading" && (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {[0, 1, 2].map((i) => (
            <Skeleton key={i} className="h-28" />
          ))}
        </div>
      )}
      {projects.status === "error" && <ErrorState message={projects.error.message} onRetry={projects.reload} />}
      {projects.status === "success" && projects.data.length === 0 && (
        <EmptyState
          title="Create your first project"
          description="Import a repository from GitHub to get started."
          action={<ButtonLink href="/projects/new">Import repository</ButtonLink>}
        />
      )}
      {projects.status === "success" && projects.data.length > 0 && (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {projects.data.map((project) => (
            <ProjectCard key={project.id} project={project} />
          ))}
        </div>
      )}
    </div>
  );
}
