-- Phase 2: GitHub sign-in, encrypted GitHub credentials, richer projects, idempotent consumers.

-- ---------------------------------------------------------------------------------------------
-- users: GitHub is now the identity provider. Email can be private on GitHub, so it is optional.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE users ALTER COLUMN email DROP NOT NULL;
ALTER TABLE users ADD COLUMN github_login VARCHAR(39);
ALTER TABLE users ADD COLUMN avatar_url VARCHAR(512);
ALTER TABLE users ADD COLUMN last_login_at TIMESTAMPTZ;
-- Backfill rows created before GitHub sign-in existed (the Phase 1 local dev user).
UPDATE users SET github_login = left(github_id, 39) WHERE github_login IS NULL;
ALTER TABLE users ALTER COLUMN github_login SET NOT NULL;

-- ---------------------------------------------------------------------------------------------
-- github_credentials: the user's GitHub OAuth token, encrypted at rest (AES-256-GCM, see
-- SecretCipher). Kept out of `users` so the secret can never ride along with a User entity.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE github_credentials (
    user_id                 UUID         PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    encrypted_access_token  TEXT         NOT NULL,
    scopes                  VARCHAR(512),
    created_at              TIMESTAMPTZ  NOT NULL,
    updated_at              TIMESTAMPTZ  NOT NULL
);

-- ---------------------------------------------------------------------------------------------
-- projects
-- ---------------------------------------------------------------------------------------------
ALTER TABLE projects ADD COLUMN owner VARCHAR(39);
UPDATE projects SET owner = split_part(repository, '/', 1) WHERE owner IS NULL;
ALTER TABLE projects ALTER COLUMN owner SET NOT NULL;

-- GitHub's numeric id survives repository renames and transfers.
ALTER TABLE projects ADD COLUMN github_repository_id BIGINT;
ALTER TABLE projects ADD COLUMN default_build_command VARCHAR(500);
ALTER TABLE projects ADD COLUMN default_start_command VARCHAR(500);

UPDATE projects SET framework = 'VITE' WHERE framework = 'VITE_REACT';
ALTER TABLE projects ADD CONSTRAINT ck_projects_framework
    CHECK (framework IN ('UNKNOWN', 'REACT', 'VITE', 'NEXTJS', 'NODE'));

-- "My projects, newest first" is the most common query.
CREATE INDEX idx_projects_user_created ON projects (user_id, created_at DESC);

-- ---------------------------------------------------------------------------------------------
-- processed_events: idempotent-consumer ledger. A consumer records (consumer, event_id) in the
-- same statement that decides whether to act, so redelivered Kafka events are no-ops.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE processed_events (
    consumer       VARCHAR(64)  NOT NULL,
    event_id       UUID         NOT NULL,
    deployment_id  UUID,
    processed_at   TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (consumer, event_id)
);
