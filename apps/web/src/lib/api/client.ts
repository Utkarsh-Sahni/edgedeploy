import type { ProblemDetail } from "@/types";

export const API_URL = (process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080").replace(/\/$/, "");

/** Fired on any 401 so the auth layer can send the user back to /login. */
export const UNAUTHORIZED_EVENT = "edgedeploy:unauthorized";

const CSRF_COOKIE = "XSRF-TOKEN";
const CSRF_HEADER = "X-XSRF-TOKEN";
const UNSAFE_METHODS = new Set(["POST", "PUT", "PATCH", "DELETE"]);

/** Error carrying the API's ProblemDetail so UIs can show field errors and react to `code`. */
export class ApiError extends Error {
  constructor(public readonly problem: ProblemDetail) {
    super(problem.detail ?? problem.title ?? `Request failed with status ${problem.status}`);
    this.name = "ApiError";
  }

  get status(): number {
    return this.problem.status;
  }

  get code(): string | undefined {
    return this.problem.code;
  }

  get fieldErrors(): Record<string, string> {
    return this.problem.errors ?? {};
  }

  /** GitHub revoked/expired our token: the fix is signing in again. */
  get needsGitHubReauth(): boolean {
    return this.code === "github_reauth_required";
  }
}

export function toApiError(error: unknown): ApiError {
  return error instanceof ApiError ? error : new ApiError({ status: 0, title: "Unexpected error", detail: String(error) });
}

function readCookie(name: string): string | undefined {
  if (typeof document === "undefined") return undefined;
  const match = document.cookie.split("; ").find((c) => c.startsWith(`${name}=`));
  return match ? decodeURIComponent(match.slice(name.length + 1)) : undefined;
}

/**
 * The API uses Spring Security's cookie-to-header CSRF pattern: read the XSRF-TOKEN cookie (issued by
 * any API response, or explicitly by /api/auth/csrf) and echo it in X-XSRF-TOKEN on mutating requests.
 */
async function csrfToken(forceRefresh = false): Promise<string | undefined> {
  if (!forceRefresh) {
    const existing = readCookie(CSRF_COOKIE);
    if (existing) return existing;
  }
  await fetch(`${API_URL}/api/auth/csrf`, { credentials: "include", cache: "no-store" });
  return readCookie(CSRF_COOKIE);
}

interface RequestOptions {
  method?: "GET" | "POST" | "PUT" | "PATCH" | "DELETE";
  body?: unknown;
  signal?: AbortSignal;
}

export async function request<T>(path: string, options: RequestOptions = {}, isRetry = false): Promise<T> {
  const method = options.method ?? "GET";
  const headers: Record<string, string> = { Accept: "application/json" };
  if (options.body !== undefined) headers["Content-Type"] = "application/json";
  if (UNSAFE_METHODS.has(method)) {
    const token = await csrfToken(isRetry);
    if (token) headers[CSRF_HEADER] = token;
  }

  let response: Response;
  try {
    response = await fetch(`${API_URL}${path}`, {
      method,
      headers,
      body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
      // The session cookie is HttpOnly and lives on the API origin; the browser attaches it.
      credentials: "include",
      cache: "no-store",
      signal: options.signal,
    });
  } catch (error) {
    if (error instanceof DOMException && error.name === "AbortError") throw error;
    throw new ApiError({ status: 0, title: "Network error", detail: `Cannot reach the API at ${API_URL}. Is it running?` });
  }

  if (response.ok) {
    return (response.status === 204 ? undefined : await response.json()) as T;
  }

  let problem: ProblemDetail = { status: response.status, title: response.statusText };
  try {
    problem = { ...problem, ...((await response.json()) as ProblemDetail) };
  } catch {
    // Non-JSON error body (e.g. a proxy error page); keep the status line.
  }

  // A stale CSRF cookie: fetch a fresh one and retry exactly once.
  if (response.status === 403 && problem.code === "csrf" && !isRetry) {
    return request<T>(path, options, true);
  }
  if (response.status === 401 && typeof window !== "undefined") {
    window.dispatchEvent(new CustomEvent(UNAUTHORIZED_EVENT, { detail: problem }));
  }
  throw new ApiError(problem);
}
