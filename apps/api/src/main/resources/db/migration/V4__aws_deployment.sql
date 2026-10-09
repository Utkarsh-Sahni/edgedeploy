-- Phase 4: deploying to AWS ECS Fargate.

-- ---------------------------------------------------------------------------------------------
-- Timeline milestones for the push/deploy half of the pipeline.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE deployment_logs DROP CONSTRAINT ck_deployment_logs_step;
ALTER TABLE deployment_logs ADD CONSTRAINT ck_deployment_logs_step
    CHECK (step IS NULL OR step IN ('QUEUED', 'CLONE', 'COMMIT', 'FRAMEWORK', 'IMAGE', 'PUSH', 'DEPLOY', 'HEALTH', 'LIVE'));

-- ---------------------------------------------------------------------------------------------
-- What each deployment ran on, kept so that any deployment can be rolled back to later:
-- the immutable image digest and the ECS task definition revision that referenced it.
-- Identifiers only; no AWS response payloads.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE deployments ADD COLUMN image_digest VARCHAR(100);
ALTER TABLE deployments ADD COLUMN ecs_cluster VARCHAR(255);
ALTER TABLE deployments ADD COLUMN ecs_service VARCHAR(255);
ALTER TABLE deployments ADD COLUMN ecs_task_definition_arn VARCHAR(512);
ALTER TABLE deployments ADD COLUMN ecs_task_arn VARCHAR(512);

-- "The deployment currently serving this project" and "the last good one to roll back to".
CREATE INDEX idx_deployments_project_running ON deployments (project_id, number DESC) WHERE status = 'RUNNING';
