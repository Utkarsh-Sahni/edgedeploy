"use client";

import { githubLoginUrl } from "@/lib/api";

export default function HomePage() {
  return (
    <main className="mx-auto flex min-h-screen max-w-6xl flex-col px-6 py-10">
      <header className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <div className="h-9 w-9 rounded-xl bg-accent/20 ring-1 ring-accent/40" />
          <span className="text-lg font-semibold tracking-tight">EdgeDeploy</span>
        </div>
        <a
          href={githubLoginUrl()}
          className="rounded-full bg-white px-4 py-2 text-sm font-medium text-ink-950 hover:bg-zinc-200"
        >
          Continue with GitHub
        </a>
      </header>

      <section className="mt-24 grid gap-12 lg:grid-cols-2 lg:items-center">
        <div>
          <p className="text-sm uppercase tracking-[0.3em] text-accent">Platform engineering</p>
          <h1 className="mt-4 text-5xl font-semibold leading-tight tracking-tight">
            Git push. Docker build. Live URL.
          </h1>
          <p className="mt-6 max-w-xl text-lg text-zinc-400">
            Connect a GitHub repository, queue a deployment, and let the Spring Boot worker clone,
            build, push to ECR, and roll out on ECS. The API never blocks on the build.
          </p>
          <a
            href={githubLoginUrl()}
            className="mt-8 inline-flex rounded-full bg-accent px-5 py-3 text-sm font-semibold text-ink-950"
          >
            Open the dashboard
          </a>
        </div>
        <div className="panel p-6 font-mono text-sm text-zinc-300">
          <p className="text-zinc-500">deployment.requested</p>
          <pre className="mt-4 overflow-x-auto text-accent/90">{`{
  "deploymentId": "8f2a1c...",
  "repository": "acme/web",
  "branch": "main",
  "commitSha": "c0ffee"
}`}</pre>
          <div className="mt-6 space-y-2 text-zinc-400">
            <p>1. Clone + checkout</p>
            <p>2. docker build → ECR</p>
            <p>3. ECS Fargate rollout</p>
            <p>4. Health check → https://8f2a1c12.localhost</p>
          </div>
        </div>
      </section>
    </main>
  );
}
