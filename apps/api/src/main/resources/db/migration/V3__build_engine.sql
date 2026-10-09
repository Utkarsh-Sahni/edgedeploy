-- Phase 3: real build pipeline.

-- ---------------------------------------------------------------------------------------------
-- deployments: IMAGE_BUILT status; human-friendly per-project numbers ("Deployment #42").
-- ---------------------------------------------------------------------------------------------
ALTER TABLE deployments DROP CONSTRAINT ck_deployments_status;
ALTER TABLE deployments ADD CONSTRAINT ck_deployments_status CHECK (status IN
    ('QUEUED', 'BUILDING', 'IMAGE_BUILT', 'PUSHING', 'DEPLOYING', 'HEALTH_CHECK', 'RUNNING', 'FAILED', 'STOPPED'));

ALTER TABLE deployments ADD COLUMN number INT;
UPDATE deployments d
   SET number = numbered.rn
  FROM (SELECT id, row_number() OVER (PARTITION BY project_id ORDER BY created_at, id) AS rn FROM deployments) numbered
 WHERE d.id = numbered.id;
ALTER TABLE deployments ALTER COLUMN number SET NOT NULL;
ALTER TABLE deployments ADD CONSTRAINT uq_deployments_project_number UNIQUE (project_id, number);

-- ---------------------------------------------------------------------------------------------
-- deployment_logs
--   seq:  strictly increasing insert order. Timestamps tie when build output arrives in bursts;
--         seq gives a total order and lets live log streams resume exactly where they left off.
--   step: timeline milestone this line completes (INFO) or fails (ERROR); NULL for plain output.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE deployment_logs ADD COLUMN seq BIGINT GENERATED ALWAYS AS IDENTITY;
ALTER TABLE deployment_logs ADD COLUMN step VARCHAR(16);
ALTER TABLE deployment_logs ADD CONSTRAINT ck_deployment_logs_step
    CHECK (step IS NULL OR step IN ('QUEUED', 'CLONE', 'COMMIT', 'FRAMEWORK', 'IMAGE'));

DROP INDEX idx_deployment_logs_deployment_ts;
CREATE INDEX idx_deployment_logs_deployment_seq ON deployment_logs (deployment_id, seq);
