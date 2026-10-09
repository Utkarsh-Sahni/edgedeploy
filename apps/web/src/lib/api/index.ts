import type {
  CreateProjectInput,
  CurrentUser,
  Deployment,
  DeploymentLog,
  EnvironmentVariable,
  GitHubBranch,
  GitHubRepository,
  Page,
  Project,
  UpdateProjectInput,
} from "@/types";
import { API_URL, request } from "./client";

export { API_URL, ApiError, UNAUTHORIZED_EVENT, toApiError } from "./client";

const enc = encodeURIComponent;

/** Typed API surface. Components call these; they never call fetch() directly. */
export const api = {
  auth: {
    me: () => request<CurrentUser>("/api/auth/me"),
    logout: () => request<void>("/api/auth/logout", { method: "POST" }),
    /** Full-page navigation: the OAuth dance happens on the API origin, then GitHub redirects back. */
    signInUrl: `${API_URL}/oauth2/authorization/github`,
  },

  github: {
    repositories: (page = 1, perPage = 50) =>
      request<Page<GitHubRepository>>(`/api/github/repositories?page=${page}&perPage=${perPage}`),
    branches: (owner: string, repo: string) =>
      request<Page<GitHubBranch>>(`/api/github/repositories/${enc(owner)}/${enc(repo)}/branches`),
  },

  projects: {
    list: () => request<Project[]>("/api/projects"),
    get: (id: string) => request<Project>(`/api/projects/${enc(id)}`),
    create: (input: CreateProjectInput) => request<Project>("/api/projects", { method: "POST", body: input }),
    update: (id: string, input: UpdateProjectInput) =>
      request<Project>(`/api/projects/${enc(id)}`, { method: "PATCH", body: input }),
    remove: (id: string) => request<void>(`/api/projects/${enc(id)}`, { method: "DELETE" }),
  },

  environment: {
    list: (projectId: string) => request<EnvironmentVariable[]>(`/api/projects/${enc(projectId)}/env`),
    set: (projectId: string, key: string, value: string) =>
      request<EnvironmentVariable>(`/api/projects/${enc(projectId)}/env/${enc(key)}`, { method: "PUT", body: { value } }),
    remove: (projectId: string, key: string) =>
      request<void>(`/api/projects/${enc(projectId)}/env/${enc(key)}`, { method: "DELETE" }),
  },

  deployments: {
    recent: (limit = 10) => request<Deployment[]>(`/api/deployments?limit=${limit}`),
    forProject: (projectId: string, limit = 20) =>
      request<Deployment[]>(`/api/projects/${enc(projectId)}/deployments?limit=${limit}`),
    trigger: (projectId: string, commitSha?: string) =>
      request<Deployment>(`/api/projects/${enc(projectId)}/deployments`, {
        method: "POST",
        body: commitSha ? { commitSha } : {},
      }),
    get: (id: string) => request<Deployment>(`/api/deployments/${enc(id)}`),
    logs: (id: string, after = 0) => request<DeploymentLog[]>(`/api/deployments/${enc(id)}/logs?after=${after}&limit=5000`),
    cancel: (id: string) => request<Deployment>(`/api/deployments/${enc(id)}/cancel`, { method: "POST" }),
    eventsUrl: (id: string) => `${API_URL}/api/deployments/${enc(id)}/events`,
    logStreamUrl: (id: string) => `${API_URL}/api/deployments/${enc(id)}/logs/stream`,
  },
};
