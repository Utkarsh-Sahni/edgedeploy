"use client";

import Link from "next/link";
import { DeploymentTable } from "@/components/DeploymentTable";
import { ProjectCard } from "@/components/ProjectCard";
import { StatusBadge } from "@/components/StatusBadge";
import { ButtonLink, Card, EmptyState, ErrorState, Skeleton } from "@/components/ui";
import { useAsync } from "@/hooks/useAsync";
import { api } from "@/lib/api";
import { useAuth } from "@/lib/auth/AuthProvider";
import { isSettled, type Deployment } from "@/types";

const RECENT_DEPLOYMENTS = 10;

export default function DashboardPage() {
  const { state: auth } = useAuth();
  const overview = useAsync(
    async () => {
      const [projects, deployments] = await Promise.all([api.projects.list(), api.deployments.recent(RECENT_DEPLOYMENTS)]);
      return { projects, deployments };
    },
    [],
  );

  const greeting = auth.status === "authenticated" ? auth.user.name.split(" ")[0] : "";

  return (
    <div className="space-y-8">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">Welcome back{greeting && `, ${greeting}`}</h1>
          <p className="mt-1 text-sm text-zinc-500">Your projects and latest deployments at a glance.</p>
        </div>
        <ButtonLink href="/projects/new">New project</ButtonLink>
      </div>

      {overview.status === "loading" && (
        <div className="grid gap-4 sm:grid-cols-4">
          {[0, 1, 2, 3].map((i) => (
            <Skeleton key={i} className="h-24" />
          ))}
        </div>
      )}

      {overview.status === "error" && <ErrorState message={overview.error.message} onRetry={overview.reload} />}

      {overview.status === "success" && overview.data.projects.length === 0 && (
        <EmptyState
          title="Create your first project"
          description="Pick a GitHub repository and branch, then deploy it with one click."
          action={<ButtonLink href="/projects/new">Create your first project</ButtonLink>}
        />
      )}

      {overview.status === "success" && overview.data.projects.length > 0 && (
        <>
          <Stats projectCount={overview.data.projects.length} deployments={overview.data.deployments} />

          <section>
            <div className="mb-3 flex items-center justify-between">
              <h2 className="font-medium">Recent projects</h2>
              <Link href="/projects" className="text-sm text-zinc-500 hover:text-zinc-900 dark:hover:text-white">
                View all
              </Link>
            </div>
            <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
              {overview.data.projects.slice(0, 6).map((project) => (
                <ProjectCard key={project.id} project={project} />
              ))}
            </div>
          </section>

          <section>
            <h2 className="mb-3 font-medium">Recent deployments</h2>
            <Card>
              {overview.data.deployments.length === 0 ? (
                <p className="p-8 text-center text-sm text-zinc-500">No deployments yet. Open a project and click Deploy.</p>
              ) : (
                <DeploymentTable deployments={overview.data.deployments} showProject />
              )}
            </Card>
          </section>
        </>
      )}
    </div>
  );
}

function Stats({ projectCount, deployments }: { projectCount: number; deployments: Deployment[] }) {
  const active = deployments.filter((d) => !isSettled(d.status)).length;
  const settled = deployments.filter((d) => isSettled(d.status));
  const succeeded = settled.filter((d) => d.status === "RUNNING").length;
  const successRate = settled.length > 0 ? `${Math.round((succeeded / settled.length) * 100)}%` : "—";
  const latest = deployments[0];

  return (
    <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
      <Stat label="Projects" value={String(projectCount)} />
      <Stat label="Active deployments" value={String(active)} />
      <Stat label="Success rate" value={successRate} hint={`last ${deployments.length} deployments`} />
      <Card className="p-5">
        <p className="text-xs font-medium uppercase tracking-wide text-zinc-500">Latest deployment</p>
        <div className="mt-2">{latest ? <StatusBadge status={latest.status} /> : <span className="text-sm text-zinc-500">None yet</span>}</div>
        {latest && <p className="mt-2 truncate text-xs text-zinc-500">{latest.projectName}</p>}
      </Card>
    </div>
  );
}

function Stat({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <Card className="p-5">
      <p className="text-xs font-medium uppercase tracking-wide text-zinc-500">{label}</p>
      <p className="mt-1 text-2xl font-semibold">{value}</p>
      {hint && <p className="text-xs text-zinc-500">{hint}</p>}
    </Card>
  );
}
