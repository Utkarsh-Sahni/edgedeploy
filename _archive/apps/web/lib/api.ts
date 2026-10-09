const API_BASE = process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080";

export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message);
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    ...init,
    credentials: "include",
    headers: {
      "Content-Type": "application/json",
      ...(init?.headers || {}),
    },
  });
  if (response.status === 204) {
    return undefined as T;
  }
  if (!response.ok) {
    const body = await response.json().catch(() => ({ message: response.statusText }));
    throw new ApiError(response.status, body.message || "Request failed");
  }
  return response.json() as Promise<T>;
}

export type User = {
  id: string;
  email: string;
  name: string;
  githubId: string;
  githubLogin: string;
};

export type Project = {
  id: string;
  name: string;
  repository: string;
  branch: string;
  framework: string | null;
  latestStatus: string | null;
  latestUrl: string | null;
  createdAt: string;
  updatedAt: string;
};

export type Deployment = {
  id: string;
  projectId: string;
  commitSha: string;
  status: string;
  imageUri: string | null;
  deploymentUrl: string | null;
  errorMessage: string | null;
  startedAt: string | null;
  completedAt: string | null;
  createdAt: string;
};

export type DeploymentLog = {
  id: string;
  level: string;
  message: string;
  timestamp: string;
};

export type EnvVar = {
  id: string;
  key: string;
  createdAt: string;
  updatedAt: string;
};

export type GitHubRepo = {
  fullName: string;
  defaultBranch: string;
  privateRepository: boolean;
  htmlUrl: string;
};

export type GitHubBranch = {
  name: string;
  commitSha: string;
};

export const api = {
  me: () => request<User>("/api/v1/auth/me"),
  logout: () => request<void>("/api/v1/auth/logout", { method: "POST" }),
  projects: () => request<Project[]>("/api/v1/projects"),
  project: (id: string) => request<Project>(`/api/v1/projects/${id}`),
  createProject: (body: { name: string; repository: string; branch: string; framework?: string }) =>
    request<Project>("/api/v1/projects", { method: "POST", body: JSON.stringify(body) }),
  deployments: (projectId: string) => request<Deployment[]>(`/api/v1/projects/${projectId}/deployments`),
  deploy: (projectId: string) =>
    request<Deployment>(`/api/v1/projects/${projectId}/deployments`, { method: "POST", body: "{}" }),
  deployment: (id: string) => request<Deployment>(`/api/v1/deployments/${id}`),
  logs: (id: string) => request<DeploymentLog[]>(`/api/v1/deployments/${id}/logs`),
  stop: (id: string) => request<Deployment>(`/api/v1/deployments/${id}/stop`, { method: "POST" }),
  env: (projectId: string) => request<EnvVar[]>(`/api/v1/projects/${projectId}/env`),
  upsertEnv: (projectId: string, key: string, value: string) =>
    request<EnvVar>(`/api/v1/projects/${projectId}/env`, {
      method: "PUT",
      body: JSON.stringify({ key, value }),
    }),
  deleteEnv: (projectId: string, envId: string) =>
    request<void>(`/api/v1/projects/${projectId}/env/${envId}`, { method: "DELETE" }),
  repos: () => request<GitHubRepo[]>("/api/v1/github/repos"),
  branches: (repository: string) =>
    request<GitHubBranch[]>(`/api/v1/github/branches?repository=${encodeURIComponent(repository)}`),
};

export function githubLoginUrl() {
  return `${API_BASE}/oauth2/authorization/github`;
}

export function statusTone(status?: string | null) {
  switch (status) {
    case "RUNNING":
      return "bg-emerald-500/15 text-emerald-300 border-emerald-500/30";
    case "FAILED":
      return "bg-rose-500/15 text-rose-300 border-rose-500/30";
    case "STOPPED":
      return "bg-zinc-500/15 text-zinc-300 border-zinc-500/30";
    case "QUEUED":
      return "bg-sky-500/15 text-sky-300 border-sky-500/30";
    default:
      return "bg-amber-500/15 text-amber-200 border-amber-500/30";
  }
}
