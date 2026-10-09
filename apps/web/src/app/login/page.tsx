"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useEffect } from "react";
import { Banner, Button, Card } from "@/components/ui";
import { useAuth } from "@/lib/auth/AuthProvider";

const ERRORS: Record<string, string> = {
  oauth: "GitHub sign-in did not complete. Please try again.",
};

function LoginContent() {
  const { state, signIn } = useAuth();
  const router = useRouter();
  const error = useSearchParams().get("error");

  useEffect(() => {
    if (state.status === "authenticated") router.replace("/dashboard");
  }, [state.status, router]);

  return (
    <main className="grid min-h-screen place-items-center px-4">
      <Card className="w-full max-w-sm p-8">
        <div className="mb-6 flex items-center gap-2 font-semibold tracking-tight">
          <span aria-hidden className="grid h-7 w-7 place-items-center rounded-md bg-zinc-900 text-sm text-white dark:bg-white dark:text-zinc-900">
            ▲
          </span>
          EdgeDeploy
        </div>
        <h1 className="text-xl font-semibold">Sign in</h1>
        <p className="mt-1 text-sm text-zinc-500">Deploy your GitHub repositories in one click.</p>

        {error && (
          <div className="mt-5">
            <Banner>{ERRORS[error] ?? "Sign-in failed. Please try again."}</Banner>
          </div>
        )}
        {state.status === "error" && (
          <div className="mt-5">
            <Banner>{state.message}</Banner>
          </div>
        )}

        <Button className="mt-6 w-full" onClick={signIn} disabled={state.status === "loading" || state.status === "authenticated"}>
          <svg aria-hidden viewBox="0 0 16 16" className="h-4 w-4 fill-current">
            <path d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.013 8.013 0 0016 8c0-4.42-3.58-8-8-8z" />
          </svg>
          Continue with GitHub
        </Button>
        <p className="mt-4 text-center text-xs text-zinc-500">
          EdgeDeploy asks for repository access so it can list and build your projects.
        </p>
      </Card>
    </main>
  );
}

export default function LoginPage() {
  // useSearchParams needs a Suspense boundary for static prerendering.
  return (
    <Suspense>
      <LoginContent />
    </Suspense>
  );
}
