"use client";

import { useEffect, useMemo, useState } from "react";
import { Banner, Button, Card, ErrorState, Skeleton, inputClass } from "@/components/ui";
import { ApiError, api, toApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth/AuthProvider";
import { timeAgo } from "@/lib/format";
import type { GitHubRepository } from "@/types";

const PAGE_SIZE = 50;

/** Lists the user's GitHub repositories (most recently pushed first) with search and "load more". */
export function RepositoryPicker({ onSelect }: { onSelect: (repo: GitHubRepository) => void }) {
  const { signIn } = useAuth();
  const [repos, setRepos] = useState<GitHubRepository[]>([]);
  const [page, setPage] = useState(1);
  const [hasNext, setHasNext] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<ApiError | null>(null);
  const [query, setQuery] = useState("");

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    api.github
      .repositories(page, PAGE_SIZE)
      .then((result) => {
        if (cancelled) return;
        setRepos((current) => (page === 1 ? result.items : [...current, ...result.items]));
        setHasNext(result.hasNext);
        setError(null);
      })
      .catch((e) => !cancelled && setError(toApiError(e)))
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, [page]);

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    return q ? repos.filter((r) => r.fullName.toLowerCase().includes(q) || r.description?.toLowerCase().includes(q)) : repos;
  }, [repos, query]);

  if (error && repos.length === 0) {
    return (
      <ErrorState
        message={error.needsGitHubReauth ? "Your GitHub authorization has expired." : error.message}
        onRetry={error.needsGitHubReauth ? undefined : () => setPage(1)}
        action={error.needsGitHubReauth ? <Button onClick={signIn}>Reconnect GitHub</Button> : undefined}
      />
    );
  }

  return (
    <Card>
      <div className="border-b border-zinc-200 p-4 dark:border-zinc-800">
        <input
          type="search"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Search repositories…"
          aria-label="Search repositories"
          className={inputClass}
        />
      </div>

      {loading && repos.length === 0 ? (
        <div className="space-y-3 p-4">
          {[0, 1, 2, 3].map((i) => (
            <Skeleton key={i} className="h-14" />
          ))}
        </div>
      ) : filtered.length === 0 ? (
        <p className="p-8 text-center text-sm text-zinc-500">
          {repos.length === 0 ? "No repositories found on your GitHub account." : `No repositories match “${query}”.`}
        </p>
      ) : (
        <ul className="max-h-[28rem] divide-y divide-zinc-200 overflow-y-auto dark:divide-zinc-800">
          {filtered.map((repo) => (
            <li key={repo.id}>
              <button
                type="button"
                onClick={() => onSelect(repo)}
                disabled={!repo.canDeploy}
                title={repo.canDeploy ? undefined : "You need write access to deploy this repository"}
                className="flex w-full items-start gap-4 px-4 py-3 text-left hover:bg-zinc-50 disabled:cursor-not-allowed disabled:opacity-50 dark:hover:bg-zinc-800/50"
              >
                <div className="min-w-0 flex-1">
                  <div className="flex items-center gap-2">
                    <span className="truncate text-sm font-medium">
                      <span className="text-zinc-500">{repo.owner}/</span>
                      {repo.name}
                    </span>
                    <span className="shrink-0 rounded-full border border-zinc-300 px-2 py-px text-[11px] text-zinc-600 dark:border-zinc-700 dark:text-zinc-400">
                      {repo.private ? "Private" : "Public"}
                    </span>
                  </div>
                  {repo.description && <p className="mt-0.5 truncate text-xs text-zinc-500">{repo.description}</p>}
                  <p className="mt-1 text-xs text-zinc-500">
                    <span className="font-mono">{repo.defaultBranch}</span>
                    {repo.language && ` · ${repo.language}`}
                    {repo.pushedAt && ` · pushed ${timeAgo(repo.pushedAt)}`}
                    {!repo.canDeploy && " · read-only"}
                  </p>
                </div>
                <span className="self-center text-sm text-zinc-400">{repo.canDeploy ? "Select →" : ""}</span>
              </button>
            </li>
          ))}
        </ul>
      )}

      {error && repos.length > 0 && (
        <div className="p-4">
          <Banner>{error.message}</Banner>
        </div>
      )}
      {hasNext && (
        <div className="border-t border-zinc-200 p-3 text-center dark:border-zinc-800">
          <Button variant="secondary" onClick={() => setPage((p) => p + 1)} disabled={loading}>
            {loading ? "Loading…" : "Load more repositories"}
          </Button>
        </div>
      )}
    </Card>
  );
}
