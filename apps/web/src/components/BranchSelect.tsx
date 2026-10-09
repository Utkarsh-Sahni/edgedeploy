"use client";

import { inputClass } from "@/components/ui";
import { useAsync } from "@/hooks/useAsync";
import { api } from "@/lib/api";

/** Branch dropdown backed by GET /api/github/repositories/{owner}/{repo}/branches. */
export function BranchSelect({
  id,
  repository,
  value,
  onChange,
}: {
  id: string;
  repository: string;
  value: string;
  onChange: (branch: string) => void;
}) {
  const [owner = "", repo = ""] = repository.split("/");
  const branches = useAsync(() => api.github.branches(owner, repo), [owner, repo]);

  if (branches.status === "loading") {
    return (
      <select id={id} disabled className={inputClass}>
        <option>Loading branches…</option>
      </select>
    );
  }
  if (branches.status === "error") {
    return (
      <div className="space-y-1">
        <input id={id} value={value} onChange={(e) => onChange(e.target.value)} className={inputClass} />
        <p className="text-xs text-amber-700 dark:text-amber-400">
          Couldn&apos;t load branches ({branches.error.message}). Type the branch name instead.
        </p>
      </div>
    );
  }

  const names = branches.data.items.map((b) => b.name);
  const options = names.includes(value) || !value ? names : [value, ...names];
  return (
    <select id={id} value={value} onChange={(e) => onChange(e.target.value)} className={inputClass}>
      {options.map((name) => (
        <option key={name} value={name}>
          {name}
        </option>
      ))}
    </select>
  );
}
