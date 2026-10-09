"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { BranchSelect } from "@/components/BranchSelect";
import { RepositoryPicker } from "@/components/RepositoryPicker";
import { Banner, Button, Card, Field, inputClass } from "@/components/ui";
import { api, toApiError } from "@/lib/api";
import { FRAMEWORKS, FRAMEWORK_LABELS, type Framework, type GitHubRepository } from "@/types";

/** Step 1: choose a repository. Step 2: choose a branch, name the project, create it. */
export default function NewProjectPage() {
  const [repo, setRepo] = useState<GitHubRepository | null>(null);

  return (
    <div className="mx-auto max-w-3xl space-y-6">
      <div>
        <Link href="/projects" className="text-sm text-zinc-500 hover:text-zinc-900 dark:hover:text-white">
          ← Projects
        </Link>
        <h1 className="mt-2 text-2xl font-semibold tracking-tight">New project</h1>
        <Steps current={repo ? 2 : 1} />
      </div>

      {repo ? <ConfigureProject repo={repo} onBack={() => setRepo(null)} /> : <RepositoryPicker onSelect={setRepo} />}
    </div>
  );
}

function Steps({ current }: { current: 1 | 2 }) {
  const steps = ["Import repository", "Configure project"];
  return (
    <ol className="mt-4 flex gap-6 text-sm">
      {steps.map((label, i) => {
        const n = i + 1;
        const done = n < current;
        const active = n === current;
        return (
          <li key={label} className={`flex items-center gap-2 ${active ? "font-medium" : "text-zinc-500"}`}>
            <span
              className={`grid h-5 w-5 place-items-center rounded-full text-xs ${
                done || active ? "bg-zinc-900 text-white dark:bg-white dark:text-zinc-900" : "bg-zinc-200 dark:bg-zinc-800"
              }`}
            >
              {done ? "✓" : n}
            </span>
            {label}
          </li>
        );
      })}
    </ol>
  );
}

function ConfigureProject({ repo, onBack }: { repo: GitHubRepository; onBack: () => void }) {
  const router = useRouter();
  const [name, setName] = useState(repo.name);
  const [branch, setBranch] = useState(repo.defaultBranch);
  const [framework, setFramework] = useState<Framework>("UNKNOWN");
  const [buildCommand, setBuildCommand] = useState("");
  const [startCommand, setStartCommand] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setSubmitting(true);
    setError(null);
    setFieldErrors({});
    try {
      const project = await api.projects.create({
        name: name.trim(),
        repository: repo.fullName,
        branch,
        framework,
        buildCommand: buildCommand.trim() || undefined,
        startCommand: startCommand.trim() || undefined,
      });
      router.push(`/projects/${project.id}`);
    } catch (e) {
      const apiError = toApiError(e);
      setFieldErrors(apiError.fieldErrors);
      setError(apiError.message);
      setSubmitting(false);
    }
  }

  return (
    <Card className="p-6">
      <div className="mb-6 flex items-center justify-between gap-4 rounded-lg bg-zinc-50 px-4 py-3 dark:bg-zinc-800/50">
        <div className="min-w-0">
          <p className="truncate text-sm font-medium">{repo.fullName}</p>
          <p className="text-xs text-zinc-500">{repo.private ? "Private" : "Public"} repository</p>
        </div>
        <Button variant="secondary" type="button" onClick={onBack}>
          Change
        </Button>
      </div>

      <form onSubmit={handleSubmit} className="space-y-5" noValidate>
        {error && <Banner>{error}</Banner>}
        <div className="grid gap-5 sm:grid-cols-2">
          <Field label="Project name" htmlFor="name" error={fieldErrors.name}>
            <input id="name" value={name} onChange={(e) => setName(e.target.value)} required maxLength={100} className={inputClass} />
          </Field>
          <Field label="Branch" htmlFor="branch" error={fieldErrors.branch} hint="Deployments build the latest commit on this branch">
            <BranchSelect id="branch" repository={repo.fullName} value={branch} onChange={setBranch} />
          </Field>
          <Field label="Framework" htmlFor="framework" error={fieldErrors.framework}>
            <select id="framework" value={framework} onChange={(e) => setFramework(e.target.value as Framework)} className={inputClass}>
              {FRAMEWORKS.map((f) => (
                <option key={f} value={f}>
                  {FRAMEWORK_LABELS[f]}
                </option>
              ))}
            </select>
          </Field>
        </div>

        <details className="rounded-lg border border-zinc-200 px-4 py-3 dark:border-zinc-800">
          <summary className="cursor-pointer text-sm font-medium">Build settings (optional)</summary>
          <div className="mt-4 grid gap-5 sm:grid-cols-2">
            <Field label="Build command" htmlFor="buildCommand" error={fieldErrors.buildCommand}>
              <input id="buildCommand" value={buildCommand} onChange={(e) => setBuildCommand(e.target.value)} placeholder="npm run build" className={`${inputClass} font-mono`} />
            </Field>
            <Field label="Start command" htmlFor="startCommand" error={fieldErrors.startCommand}>
              <input id="startCommand" value={startCommand} onChange={(e) => setStartCommand(e.target.value)} placeholder="npm start" className={`${inputClass} font-mono`} />
            </Field>
          </div>
        </details>

        {fieldErrors.repository && <Banner>{fieldErrors.repository}</Banner>}

        <div className="flex justify-end">
          <Button type="submit" disabled={submitting || !name.trim() || !branch}>
            {submitting ? "Creating…" : "Create project"}
          </Button>
        </div>
      </form>
    </Card>
  );
}
