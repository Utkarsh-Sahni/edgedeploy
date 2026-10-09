"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { api, githubLoginUrl, type User } from "@/lib/api";

export function Shell({ children }: { children: React.ReactNode }) {
  const router = useRouter();
  const [user, setUser] = useState<User | null>(null);

  useEffect(() => {
    api
      .me()
      .then(setUser)
      .catch(() => {
        window.location.href = githubLoginUrl();
      });
  }, []);

  async function logout() {
    await api.logout();
    router.push("/");
  }

  return (
    <div className="mx-auto min-h-screen max-w-6xl px-6 py-8">
      <header className="mb-8 flex items-center justify-between">
        <Link href="/dashboard" className="flex items-center gap-3">
          <div className="h-8 w-8 rounded-lg bg-accent/20 ring-1 ring-accent/40" />
          <span className="font-semibold">EdgeDeploy</span>
        </Link>
        <div className="flex items-center gap-4 text-sm text-zinc-400">
          <Link href="/projects/new" className="rounded-full bg-accent px-3 py-1.5 font-medium text-ink-950">
            New project
          </Link>
          {user && <span>{user.githubLogin || user.email}</span>}
          <button onClick={logout} className="hover:text-white">
            Sign out
          </button>
        </div>
      </header>
      {children}
    </div>
  );
}
