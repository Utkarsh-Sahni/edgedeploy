"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { Shell } from "@/components/Shell";
import { api, type GitHubBranch, type GitHubRepo } from "@/lib/api";

export default function NewProjectPage() {
  const router = useRouter();
  const [repos, setRepos] = useState<GitHubRepo[]>([]);
  const [branches, setBranches] = useState<GitHubBranch[]>([]);
  const [name, setName] = useState("");
  const [repository, setRepository] = useState("");
  const [branch, setBranch] = useState("main");
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.repos().then(setRepos).catch((err) => setError(err.message));
  }, []);

  useEffect(() => {
    if (!repository) return;
    api.branches(repository).then((items) => {
      setBranches(items);
      const selected = items.find((item) => item.name === "main") || items[0];
      if (selected) setBranch(selected.name);
    });
  }, [repository]);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    try {
      const project = await api.createProject({ name, repository, branch });
      router.push(`/projects/${project.id}`);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to create project");
    }
  }

  return (
    <Shell>
      <h1 className="text-3xl font-semibold">New project</h1>
      <form onSubmit={submit} className="panel mt-6 max-w-xl space-y-4 p-6">
        {error && <p className="text-rose-300">{error}</p>}
        <label className="block text-sm text-zinc-400">
          Name
          <input
            className="mt-1 w-full rounded-lg border border-white/10 bg-ink-800 px-3 py-2 text-white"
            value={name}
            onChange={(e) => setName(e.target.value)}
            required
          />
        </label>
        <label className="block text-sm text-zinc-400">
          GitHub repository
          <select
            className="mt-1 w-full rounded-lg border border-white/10 bg-ink-800 px-3 py-2 text-white"
            value={repository}
            onChange={(e) => {
              setRepository(e.target.value);
              const repo = repos.find((item) => item.fullName === e.target.value);
              if (repo && !name) setName(repo.fullName.split("/")[1] || repo.fullName);
            }}
            required
          >
            <option value="">Select a repository</option>
            {repos.map((repo) => (
              <option key={repo.fullName} value={repo.fullName}>
                {repo.fullName}
              </option>
            ))}
          </select>
        </label>
        <label className="block text-sm text-zinc-400">
          Branch
          <select
            className="mt-1 w-full rounded-lg border border-white/10 bg-ink-800 px-3 py-2 text-white"
            value={branch}
            onChange={(e) => setBranch(e.target.value)}
            required
          >
            {branches.map((item) => (
              <option key={item.name} value={item.name}>
                {item.name}
              </option>
            ))}
          </select>
        </label>
        <button className="rounded-full bg-accent px-4 py-2 text-sm font-semibold text-ink-950">Create project</button>
      </form>
    </Shell>
  );
}
