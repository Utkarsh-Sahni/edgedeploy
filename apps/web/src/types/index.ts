// Mirrors the API DTOs (com.edgedeploy.dto.*) and com.edgedeploy.contracts.DeploymentStatus.

export const DEPLOYMENT_STATUSES = [
  "QUEUED",
  "BUILDING",
  "IMAGE_BUILT",
  "PUSHING",
  "DEPLOYING",
  "HEALTH_CHECK",
  "RUNNING",
  "FAILED",
  "STOPPED",
] as const;
export type DeploymentStatus = (typeof DEPLOYMENT_STATUSES)[number];

export const FRAMEWORKS = ["UNKNOWN", "REACT", "VITE", "NEXTJS", "NODE"] as const;
export type Framework = (typeof FRAMEWORKS)[number];

export const FRAMEWORK_LABELS: Record<Framework, string> = {
  UNKNOWN: "Auto / other",
  REACT: "React",
  VITE: "Vite",
  NEXTJS: "Next.js",
  NODE: "Node.js",
};

export interface CurrentUser {
  id: string;
  login: string;
  name: string;
  email: string | null;
  avatarUrl: string | null;
}

export interface Page<T> {
  items: T[];
  page: number;
  perPage: number;
  hasNext: boolean;
}

export interface GitHubRepository {
  id: number;
  name: string;
  fullName: string;
  owner: string;
  description: string | null;
  defaultBranch: string;
  private: boolean;
  htmlUrl: string;
  language: string | null;
  pushedAt: string | null;
  canDeploy: boolean;
}

export interface GitHubBranch {
  name: string;
  commitSha: string;
  protected: boolean;
}

export interface Project {
  id: string;
  name: string;
  slug: string;
  repository: string;
  owner: string;
  githubRepositoryId: number | null;
  branch: string;
  framework: Framework;
  defaultBuildCommand: string | null;
  defaultStartCommand: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface CreateProjectInput {
  name: string;
  repository: string;
  branch: string;
  framework?: Framework;
  buildCommand?: string;
  startCommand?: string;
}

/** PATCH semantics: omitted fields are unchanged; "" clears a command. */
export type UpdateProjectInput = Partial<Pick<CreateProjectInput, "name" | "branch" | "framework" | "buildCommand" | "startCommand">>;

export interface Deployment {
  id: string;
  /** Per-project sequence number ("Deployment #42"). */
  number: number;
  projectId: string;
  projectName: string;
  commitSha: string | null;
  status: DeploymentStatus;
  imageUri: string | null;
  deploymentUrl: string | null;
  errorMessage: string | null;
  startedAt: string | null;
  completedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

/** Timeline milestones, in display order (mirrors com.edgedeploy.contracts.DeploymentStep). */
export const DEPLOYMENT_STEPS = ["QUEUED", "CLONE", "COMMIT", "FRAMEWORK", "IMAGE", "PUSH", "DEPLOY", "HEALTH", "LIVE"] as const;
/** Steps that only exist when deploying to a target (AWS); local builds stop after IMAGE. */
export const DELIVERY_STEPS: readonly DeploymentStep[] = ["PUSH", "DEPLOY", "HEALTH", "LIVE"];
export type DeploymentStep = (typeof DEPLOYMENT_STEPS)[number];

/** A log line. Lines with a `step` complete (INFO) or fail (ERROR) a timeline milestone. */
export interface DeploymentLog {
  seq: number;
  level: "DEBUG" | "INFO" | "WARN" | "ERROR";
  step: DeploymentStep | null;
  message: string;
  timestamp: string;
}

/** RFC 9457 problem document produced by the API. */
export interface ProblemDetail {
  type?: string;
  title?: string;
  status: number;
  detail?: string;
  code?: string;
  requestId?: string;
  errors?: Record<string, string>;
}

/**
 * Pipeline finished (successfully or not); no further automatic transitions. IMAGE_BUILT is where the
 * pipeline ends until deployment to AWS arrives (Phase 4).
 */
export function isSettled(status: DeploymentStatus): boolean {
  return status === "IMAGE_BUILT" || status === "RUNNING" || status === "FAILED" || status === "STOPPED";
}

export function canRedeploy(status: DeploymentStatus): boolean {
  return status === "FAILED" || status === "STOPPED";
}

/** Mirrors DeploymentStatus.isCancellable(): only before the deployment target starts rolling out. */
export function isCancellable(status: DeploymentStatus): boolean {
  return status === "QUEUED" || status === "BUILDING" || status === "PUSHING";
}

export function isInProgress(status: DeploymentStatus): boolean {
  return status === "QUEUED" || status === "BUILDING" || status === "PUSHING" || status === "DEPLOYING" || status === "HEALTH_CHECK";
}

/** A project environment variable. Values are write-only and never returned by the API. */
export interface EnvironmentVariable {
  key: string;
  createdAt: string;
  updatedAt: string;
}
