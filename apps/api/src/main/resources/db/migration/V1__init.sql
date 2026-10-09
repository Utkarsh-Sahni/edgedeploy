-- EdgeDeploy core schema. The api owns this schema; the worker only reads/writes
-- deployments and deployment_logs.

CREATE TABLE users (
    id          UUID PRIMARY KEY,
    email       VARCHAR(320) NOT NULL,
    name        VARCHAR(255) NOT NULL,
    github_id   VARCHAR(64)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT uq_users_github_id UNIQUE (github_id)
);

CREATE TABLE projects (
    id          UUID PRIMARY KEY,
    user_id     UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name        VARCHAR(100) NOT NULL,
    slug        VARCHAR(63)  NOT NULL,
    repository  VARCHAR(200) NOT NULL,
    branch      VARCHAR(255) NOT NULL,
    framework   VARCHAR(32)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_projects_user_repository UNIQUE (user_id, repository),
    CONSTRAINT uq_projects_user_slug UNIQUE (user_id, slug)
);
-- uq_projects_user_* already index user_id as their leading column.

CREATE TABLE deployments (
    id              UUID PRIMARY KEY,
    project_id      UUID         NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    -- NULL means "build the branch tip"; the worker records the resolved SHA at clone time.
    commit_sha      VARCHAR(40),
    status          VARCHAR(32)  NOT NULL,
    image_uri       VARCHAR(512),
    deployment_url  VARCHAR(512),
    error_message   TEXT,
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_deployments_status CHECK (status IN
        ('QUEUED', 'BUILDING', 'PUSHING', 'DEPLOYING', 'HEALTH_CHECK', 'RUNNING', 'FAILED', 'STOPPED'))
);

-- Deployment history per project, newest first.
CREATE INDEX idx_deployments_project_created ON deployments (project_id, created_at DESC);
-- Dashboard "active deployments" and stale-deployment sweeps only care about non-terminal rows.
CREATE INDEX idx_deployments_active ON deployments (status, updated_at)
    WHERE status NOT IN ('FAILED', 'STOPPED');

CREATE TABLE deployment_logs (
    id             UUID PRIMARY KEY,
    deployment_id  UUID        NOT NULL REFERENCES deployments (id) ON DELETE CASCADE,
    level          VARCHAR(8)  NOT NULL,
    message        TEXT        NOT NULL,
    timestamp      TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_deployment_logs_level CHECK (level IN ('DEBUG', 'INFO', 'WARN', 'ERROR'))
);

CREATE INDEX idx_deployment_logs_deployment_ts ON deployment_logs (deployment_id, timestamp);

CREATE TABLE environment_variables (
    id               UUID PRIMARY KEY,
    project_id       UUID         NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    key              VARCHAR(128) NOT NULL,
    encrypted_value  TEXT         NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_env_vars_project_key UNIQUE (project_id, key)
);

CREATE TABLE domains (
    id          UUID PRIMARY KEY,
    project_id  UUID         NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    hostname    VARCHAR(253) NOT NULL,
    status      VARCHAR(32)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_domains_hostname UNIQUE (hostname)
);

CREATE INDEX idx_domains_project_id ON domains (project_id);

-- Transactional outbox: rows are written in the same transaction as the business change
-- and relayed to Kafka asynchronously, so a Kafka outage can never lose a deployment request.
CREATE TABLE outbox_events (
    id              UUID PRIMARY KEY,
    aggregate_type  VARCHAR(64)  NOT NULL,
    aggregate_id    UUID         NOT NULL,
    event_type      VARCHAR(128) NOT NULL,
    topic           VARCHAR(249) NOT NULL,
    message_key     VARCHAR(255) NOT NULL,
    payload         JSONB        NOT NULL,
    attempts        INT          NOT NULL DEFAULT 0,
    last_error      TEXT,
    created_at      TIMESTAMPTZ  NOT NULL,
    published_at    TIMESTAMPTZ
);

CREATE INDEX idx_outbox_unpublished ON outbox_events (created_at) WHERE published_at IS NULL;
