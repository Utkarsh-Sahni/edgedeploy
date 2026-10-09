"use client";

import { useRouter } from "next/navigation";
import { useEffect, type ReactNode } from "react";
import { AppShell } from "@/components/AppShell";
import { Button, ErrorState, Spinner } from "@/components/ui";
import { useAuth } from "@/lib/auth/AuthProvider";

/**
 * Guard for every signed-in page. This is a UX convenience only: the API enforces authentication
 * and ownership on every request regardless of what the browser does.
 */
export default function AuthenticatedLayout({ children }: { children: ReactNode }) {
  const { state, refresh } = useAuth();
  const router = useRouter();

  useEffect(() => {
    if (state.status === "anonymous") router.replace("/login");
  }, [state.status, router]);

  if (state.status === "authenticated") {
    return <AppShell user={state.user}>{children}</AppShell>;
  }
  if (state.status === "error") {
    return (
      <main className="mx-auto max-w-lg px-4 py-24">
        <ErrorState message={state.message} action={<Button onClick={() => void refresh()}>Retry</Button>} />
      </main>
    );
  }
  return <Spinner label={state.status === "anonymous" ? "Redirecting to sign in" : "Checking your session"} />;
}
