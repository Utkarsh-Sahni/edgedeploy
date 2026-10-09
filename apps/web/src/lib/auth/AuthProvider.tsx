"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { ApiError, UNAUTHORIZED_EVENT, api } from "@/lib/api";
import type { CurrentUser } from "@/types";

type AuthState =
  | { status: "loading" }
  | { status: "authenticated"; user: CurrentUser }
  | { status: "anonymous" }
  | { status: "error"; message: string };

interface AuthContextValue {
  state: AuthState;
  signIn: () => void;
  signOut: () => Promise<void>;
  refresh: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

/**
 * Session awareness for the dashboard. The session itself is an HttpOnly cookie on the API origin,
 * invisible to JavaScript; we learn whether it is valid by asking /api/auth/me.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AuthState>({ status: "loading" });

  const refresh = useCallback(async () => {
    try {
      setState({ status: "authenticated", user: await api.auth.me() });
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) setState({ status: "anonymous" });
      else setState({ status: "error", message: e instanceof Error ? e.message : "Could not reach the API" });
    }
  }, []);

  useEffect(() => {
    void refresh();
    const onUnauthorized = () => setState({ status: "anonymous" });
    window.addEventListener(UNAUTHORIZED_EVENT, onUnauthorized);
    return () => window.removeEventListener(UNAUTHORIZED_EVENT, onUnauthorized);
  }, [refresh]);

  const signIn = useCallback(() => {
    window.location.assign(api.auth.signInUrl);
  }, []);

  const signOut = useCallback(async () => {
    try {
      await api.auth.logout();
    } finally {
      setState({ status: "anonymous" });
    }
  }, []);

  const value = useMemo(() => ({ state, signIn, signOut, refresh }), [state, signIn, signOut, refresh]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) throw new Error("useAuth must be used inside <AuthProvider>");
  return context;
}
