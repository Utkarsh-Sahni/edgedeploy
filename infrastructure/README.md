# infrastructure/

| Path | What |
|------|------|
| `aws/edgedeploy-foundation.yml` | CloudFormation stack with the shared AWS resources for Phase 4: VPC + two public subnets (no NAT gateway), ALB without listeners, security groups, ECS cluster, task execution role, task role, and the least-privilege `edgedeploy-worker` IAM policy (plus an optional role to assume). |

Per-project resources (ECR repositories, ECS services and task definitions, target groups, ALB listeners, log groups,
SSM parameters) are created by the worker at deploy time and removed by `scripts/aws-cleanup.sh`.

Setup, costs and cleanup: [`docs/aws-setup.md`](../docs/aws-setup.md). Local development infrastructure (Postgres,
Redis, Kafka) lives in the repository-root `docker-compose.yml`.
