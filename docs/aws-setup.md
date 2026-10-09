# Deploying to AWS (ECR + ECS Fargate)

This guide takes you from an empty AWS account to an EdgeDeploy deployment that serves a real URL, and back to
an empty, non-billing account afterwards.

> ## ⚠️ Cost warning: read this first
>
> Following this guide creates **billable** AWS resources. Approximate us-east-1 list prices (October 2026;
> check the AWS pricing pages for your region):
>
> | Resource | When it is billed | Approx. cost |
> |----------|-------------------|--------------|
> | Application Load Balancer | **every hour it exists**, even with no traffic | ~USD 0.0225/h + LCUs ≈ **USD 16–20/month** |
> | Public IPv4 addresses (ALB: one per AZ, plus one per running task) | every hour | USD 0.005/h each ≈ USD 3.60/month each |
> | Fargate task, 0.25 vCPU / 0.5 GB (the default) | while running | ≈ USD 0.012/h ≈ **USD 9/month per project** (ARM64 ~20% less) |
> | ECR storage | per GB-month | USD 0.10/GB-month (lifecycle policy keeps the last 10 images) |
> | CloudWatch Logs | ingestion + storage | USD 0.50/GB ingested (retention 7 days by default) |
> | SSM Parameter Store (standard parameters) | | free |
>
> **One project running around the clock costs about USD 35–40/month. A two-hour demo costs well under USD 1.**
> When you are done, run the [cleanup](#13-cleanup): `scripts/aws-cleanup.sh --yes --delete-stack`.
>
> The setup deliberately avoids the expensive defaults: **no NAT gateway** (≈ USD 32+/month each; tasks run in
> public subnets instead, reachable only from the ALB), **no Container Insights**, short log retention, one small task
> per project, ECR lifecycle policies. Also [create a budget alert](#1-aws-account-prerequisites) before you start.
>
> **Personal project or demo? Use [budget mode](#budget-mode-no-load-balancer):** no load balancer, so nothing is
> billed while no app runs, and a running app costs about USD 0.01–0.015/hour.

## Budget mode (no load balancer)

Two settings, both on the worker, cut the cost to roughly the task itself:

| Setting | Effect |
|---------|--------|
| `AWS_ROUTING_MODE=public-ip` (stack parameter `RoutingMode=public-ip`) | No ALB. The single task gets a public IP and the URL is `http://<task-ip>:<port>` (3000 for Node.js/Next.js, 8080 for static sites). |
| `AWS_ECS_CAPACITY=fargate-spot` | Runs on Fargate Spot, spare capacity at a large discount that AWS can reclaim (ECS then starts a replacement task). |

Approximate cost per running app (0.25 vCPU / 0.5 GB, us-east-1, October 2026):

| Image architecture | Capacity actually used | Per hour (incl. public IPv4) | 24/7 for a month |
|--------------------|------------------------|------------------------------|------------------|
| ARM64 (default when the worker runs on an Apple Silicon Mac) | on-demand Fargate (Spot does not run ARM64; the worker falls back automatically and logs it) | ≈ USD 0.015 | ≈ USD 11 |
| x86 (`DOCKER_BUILD_PLATFORM=linux/amd64`) | Fargate Spot | ≈ USD 0.009 (Spot prices vary) | ≈ USD 6–7 |

The stack itself (VPC, subnets, security groups, cluster, roles) is free, so **with no app running you pay ~nothing**
(cents for ECR storage). A one-hour demo costs about 1–2 cents. Building x86 images on an Apple Silicon Mac uses
emulation and is noticeably slower; staying on ARM64 is usually the better trade.

Trade-offs, so you can describe them honestly:
- **The URL changes with every deployment** (and whenever ECS replaces the task). The dashboard always shows the
  current one: the worker checks once a minute and moves the URL to the replacement task, logging the change.
- **Brief downtime on deploy**: the old task stops once the new one is healthy, but the address switches.
- **No TLS, no stable hostname**, and Spot tasks can be interrupted (ECS restarts them within a minute or two).
- One task per project (`AWS_ECS_DESIRED_COUNT=1`, enforced at startup).

Switching an existing project between modes (or from on-demand to Spot) needs its ECS service removed first:
run `scripts/aws-cleanup.sh --yes` and deploy again. The worker refuses to change it implicitly.

## How it fits together

```
 browser ──http://<alb-dns>:10000──▶ ALB listener :10000 ──▶ target group ed-<project> ──▶ Fargate task (project A)
         ──http://<alb-dns>:10001──▶ ALB listener :10001 ──▶ target group ed-<project> ──▶ Fargate task (project B)

 worker (your machine or a server):
   docker build ─▶ docker push ─▶ ECR edgedeploy/<projectId>
   RegisterTaskDefinition (image pinned by digest) ─▶ Create/UpdateService edgedeploy-<projectId>
   wait until stable ─▶ ALB target healthy ─▶ HTTP GET the public URL ─▶ RUNNING
```

**Shared resources, created once** by the CloudFormation stack in
[`infrastructure/aws/edgedeploy-foundation.yml`](../infrastructure/aws/edgedeploy-foundation.yml): VPC, two public
subnets, internet gateway, two security groups, the ALB (without listeners), the ECS cluster, the task execution role,
the task role, and the worker's IAM policy.

**Per-project resources, created by the worker** on a project's first deployment and reused afterwards:

| Resource | Name |
|----------|------|
| ECR repository (scan on push, AES-256, lifecycle: keep last 10) | `edgedeploy/{projectId}` |
| ECS task definition family (one revision per deployment) | `edgedeploy-{projectId}` |
| ECS service (Fargate, rolling update, circuit breaker with rollback) | `edgedeploy-{projectId}` |
| Target group (IP targets, health check on `APP_HEALTH_CHECK_PATH`) | `ed-` + first 29 hex digits of the project id |
| ALB listener | first free port in `10000–10049` |
| CloudWatch log group (7-day retention) | `/edgedeploy/{projectId}` |
| SSM SecureString parameters (environment variables) | `/edgedeploy/{projectId}/env/{KEY}` |

Everything the worker creates is tagged `edgedeploy:managed=true` and `edgedeploy:project-id={projectId}`.

**Why a port per project?** Host-based routing (`project.example.com`) needs a domain and a wildcard DNS record.
Until custom domains arrive, each project gets its own ALB listener port and the URL is `http://<alb-dns>:<port>`.
One ALB serves up to 50 projects (the listener limit), and the worker refuses configurations with a larger range.

## 1. AWS account prerequisites

- An AWS account and the [AWS CLI v2](https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html)
  (used **only** for this one-time setup and cleanup; the worker itself uses the AWS SDK).
- Docker running locally (the worker builds and pushes images), JDK 21, and the rest of the
  [local setup](../README.md#getting-started).
- Pick a region and use it everywhere: `export AWS_REGION=us-east-1`.
- **Create a budget alert** so a forgotten resource cannot surprise you:

  ```bash
  ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
  aws budgets create-budget --account-id "$ACCOUNT" \
    --budget '{"BudgetName":"edgedeploy","BudgetLimit":{"Amount":"20","Unit":"USD"},"TimeUnit":"MONTHLY","BudgetType":"COST"}' \
    --notifications-with-subscribers '[{"Notification":{"NotificationType":"ACTUAL","ComparisonOperator":"GREATER_THAN","Threshold":50,"ThresholdType":"PERCENTAGE"},"Subscribers":[{"SubscriptionType":"EMAIL","Address":"you@example.com"}]}]'
  ```

- The ECS service-linked role must exist (it usually does once any ECS cluster has been created; this is harmless if it already exists):

  ```bash
  aws iam create-service-linked-role --aws-service-name ecs.amazonaws.com 2>/dev/null || true
  ```

## 2. IAM: who can do what

Three identities, each with the least it needs:

| Identity | Used by | Permissions |
|----------|---------|-------------|
| **Worker** (`edgedeploy-worker` policy) | the EdgeDeploy worker process | `ec2:DescribeNetworkInterfaces` (read-only: a task's public IP in budget mode); push to `edgedeploy/*` ECR repositories; register task definitions; create/update/describe `edgedeploy-*` services in the EdgeDeploy cluster; list/describe tasks in that cluster only; create `ed-*` target groups and listeners **on the EdgeDeploy ALB only**; create `/edgedeploy/*` log groups; write `/edgedeploy/*` SSM parameters; `iam:PassRole` for exactly the two task roles below, and only to ECS tasks |
| **Task execution role** (`edgedeploy-task-execution`) | ECS itself, while starting a task | `AmazonECSTaskExecutionRolePolicy` (pull from ECR, write logs) + `ssm:GetParameters` on `/edgedeploy/*` |
| **Task role** (`edgedeploy-task`) | the deployed application | **nothing**: deployed code is untrusted |

The worker policy is the `WorkerPolicy` resource in the template; every statement matches an SDK call in
`apps/worker/src/main/java/com/edgedeploy/worker/aws`. **Do not run the worker with `AdministratorAccess`.**

Notes:
- SecureString parameters use the AWS-managed `aws/ssm` KMS key, whose key policy already lets principals in the
  account use it through SSM, so neither the worker nor the execution role needs `kms:*`. If you switch to a
  customer-managed key, add `kms:Encrypt` (worker) and `kms:Decrypt` (execution role) on that key.
- Deletion permissions are intentionally absent: the worker never deletes anything. Cleanup runs with your own
  (admin) credentials via `scripts/aws-cleanup.sh`.

### Giving the worker those permissions

Pick one:

- **Recommended: a role you assume.** Deploy the stack with `WorkerPrincipalArn` set to your own IAM principal
  (for an SSO user, the ARN of your `AWSReservedSSO_...` role; for an IAM user, its ARN). The stack creates
  `edgedeploy-worker` trusting only that principal. Then add a profile to `~/.aws/config`:

  ```ini
  [profile edgedeploy-worker]
  role_arn = arn:aws:iam::123456789012:role/edgedeploy-worker
  source_profile = default        # or your SSO profile
  region = us-east-1
  ```

  and run the worker with `AWS_PROFILE=edgedeploy-worker`. Short-lived credentials, nothing to rotate.
- **A dedicated IAM user**: create user `edgedeploy-worker`, attach the `edgedeploy-worker` managed policy (stack
  output `WorkerPolicyArn`), create an access key and store it in `~/.aws/credentials` under a profile. Rotate it, and
  delete it after the demo.
- **On AWS** (EC2/ECS hosting the worker): attach the policy to the instance/task role; no keys at all.

Credentials **never** go into this repository, `.env`, or environment variables committed anywhere. The worker uses
the standard AWS SDK credential chain (`AWS_PROFILE`, SSO, `~/.aws/credentials`, container/instance roles) and
verifies at startup with `sts:GetCallerIdentity` that it talks to `AWS_ACCOUNT_ID`.

## 3–11. Create the shared infrastructure

One command creates the VPC (§3), public subnets (§4), security groups (§5), the ALB (§6), the ECS cluster (§7), the
task execution role (§8), the task role (§9) and the worker policy/role. Target groups (§10), listeners (§10) and log
groups (§11) are per project, so the worker creates them.

```bash
# Your own IAM principal, which will be allowed to assume the worker role. For SSO / assumed roles this
# looks up the real role ARN (SSO roles live under the aws-reserved/sso.amazonaws.com/ path).
CALLER=$(aws sts get-caller-identity --query Arn --output text)
if [[ "$CALLER" == *:assumed-role/* ]]; then
  ROLE_NAME=$(echo "$CALLER" | cut -d/ -f2)
  WORKER_PRINCIPAL=$(aws iam get-role --role-name "$ROLE_NAME" --query Role.Arn --output text)
else
  WORKER_PRINCIPAL="$CALLER"            # an IAM user
fi
echo "$WORKER_PRINCIPAL"

aws cloudformation deploy \
  --stack-name edgedeploy \
  --template-file infrastructure/aws/edgedeploy-foundation.yml \
  --capabilities CAPABILITY_NAMED_IAM \
  --parameter-overrides \
      WorkerPrincipalArn="$WORKER_PRINCIPAL" \
      AllowedIngressCidr="$(curl -s https://checkip.amazonaws.com)/32"
```

- `AllowedIngressCidr` limits who can open deployed apps; the example allows only your current IP. Use `0.0.0.0/0`
  to make them public.
- Omit `WorkerPrincipalArn` if you prefer the IAM-user option above. Do not run this as the account root user.
- The stack takes about 3–4 minutes, mostly for the ALB.

**Budget mode:** add `RoutingMode=public-ip` to `--parameter-overrides` (and `AppPort=3000` if your apps use another
port). The stack then has no ALB; the task security group admits `AppPort` and 8080 from `AllowedIngressCidr` only.

What it creates, in the terms of the setup checklist:

| § | Item | In the template |
|---|------|-----------------|
| 3 | VPC | `Vpc` 10.40.0.0/16, DNS enabled, internet gateway |
| 4 | Subnets | `PublicSubnetA/B` in two AZs (ALBs need two). Public, because tasks need a route to ECR without a NAT gateway; they are still only reachable from the ALB |
| 5 | Security groups | `AlbSecurityGroup`: TCP 10000–10049 from `AllowedIngressCidr`. `TaskSecurityGroup`: any TCP **from the ALB security group only** |
| 6 | Application Load Balancer | `LoadBalancer`, internet-facing, no listeners (the worker adds one per project) |
| 7 | ECS cluster | `Cluster`, Fargate capacity, Container Insights off |
| 8 | Task execution role | `TaskExecutionRole` |
| 9 | Task role | `TaskRole` (no permissions) |
| 10 | Target group + listener | per project, by the worker: IP target group on the task port, health check `APP_HEALTH_CHECK_PATH` expecting 200–399, deregistration delay 30 s; HTTP listener forwarding to it |
| 11 | Log group | per project, by the worker: `/edgedeploy/{projectId}`, 7-day retention, stream prefix = deployment id |

ECR repositories are per project too (created on first push), so there is nothing to create up front.

## 12. Configure and run EdgeDeploy against AWS

Print the stack outputs as environment variables:

```bash
aws cloudformation describe-stacks --stack-name edgedeploy \
  --query 'Stacks[0].Outputs[].[OutputKey,OutputValue]' --output text |
  awk '{print $1"="$2}' | sed -E \
    -e 's/^AwsRegion=/AWS_REGION=/' -e 's/^AwsAccountId=/AWS_ACCOUNT_ID=/' -e 's/^AwsEcsCluster=/AWS_ECS_CLUSTER=/' \
    -e 's/^AwsEcsExecutionRoleArn=/AWS_ECS_EXECUTION_ROLE_ARN=/' -e 's/^AwsEcsTaskRoleArn=/AWS_ECS_TASK_ROLE_ARN=/' \
    -e 's/^AwsEcsSubnetIds=/AWS_ECS_SUBNET_IDS=/' -e 's/^AwsEcsSecurityGroupId=/AWS_ECS_SECURITY_GROUP_ID=/' \
    -e 's/^AwsAlbArn=/AWS_ALB_ARN=/' -e 's/^AwsVpcId=/AWS_VPC_ID=/' | grep '^AWS_'
```

Add them to your `.env` (none of these are secrets), plus:

```bash
EDGEDEPLOY_AWS_ENABLED=true
# Budget mode only (the stack was created with RoutingMode=public-ip):
# AWS_ROUTING_MODE=public-ip
# AWS_ECS_CAPACITY=fargate-spot
AWS_PROFILE=edgedeploy-worker        # whichever profile holds the worker's permissions
ENCRYPTION_KEY=<same value as the API>   # the worker decrypts environment variables with it
```

### Environment variables reference

| Variable | Default | Meaning |
|----------|---------|---------|
| `EDGEDEPLOY_AWS_ENABLED` | `false` | `false` = local mode (deployments end at `IMAGE_BUILT`, no AWS calls). `true` = push to ECR and run on ECS |
| `AWS_REGION`, `AWS_ACCOUNT_ID` | (required) | Region and the account the credentials must belong to (checked at startup) |
| `AWS_ECR_REPOSITORY_PREFIX` | `edgedeploy` | Repositories are `{prefix}/{projectId}` |
| `AWS_ECS_CLUSTER` | (required) | Cluster name (stack output; equals the stack's `Name` parameter) |
| `AWS_ECS_EXECUTION_ROLE_ARN`, `AWS_ECS_TASK_ROLE_ARN` | (required / optional) | The two task roles |
| `AWS_ECS_SUBNET_IDS` | (required) | Comma-separated subnets for tasks |
| `AWS_ECS_SECURITY_GROUP_ID` | (required) | Task security group |
| `AWS_ECS_ASSIGN_PUBLIC_IP` | `true` | Needed in public subnets without NAT |
| `AWS_ECS_CPU`, `AWS_ECS_MEMORY` | `256`, `512` | Fargate size (validated against the allowed combinations) |
| `AWS_ECS_DESIRED_COUNT` | `1` | Tasks per project |
| `AWS_ECS_CONTAINER_PORT` | `3000` | Port Node/Next.js apps listen on (`PORT` is injected); static sites use 8080 |
| `AWS_ECS_SECRETS_MODE` | `ssm` | `ssm`: env vars become SSM SecureString parameters referenced by the task definition. `environment`: plain task-definition environment (visible to anyone with `ecs:DescribeTaskDefinition`; demo only) |
| `AWS_ROUTING_MODE` | `alb` | `alb`: shared load balancer, stable URL. `public-ip`: [budget mode](#budget-mode-no-load-balancer) |
| `AWS_ECS_CAPACITY` | `fargate` | `fargate` (on-demand) or `fargate-spot` (cheaper, interruptible; ARM64 images fall back to on-demand) |
| `AWS_ALB_ARN`, `AWS_VPC_ID` | (required in `alb` mode) | The shared ALB and its VPC |
| `AWS_ALB_LISTENER_PORT_START`/`_END` | `10000`/`10049` | Listener ports handed out to projects (≤ 50, must match the ALB security group) |
| `AWS_LOG_GROUP`, `AWS_LOG_RETENTION_DAYS` | `/edgedeploy`, `7` | Log group prefix and retention |
| `APP_HEALTH_CHECK_PATH` | `/` | Path probed by the ALB and by the worker's final HTTP check |
| `AWS_MAX_ATTEMPTS` | `5` | SDK attempts for throttling/5xx (exponential backoff with jitter). Invalid credentials/permissions/config fail immediately |
| `IMAGE_PUSH_TIMEOUT_SECONDS`, `DEPLOY_ROLLOUT_TIMEOUT_SECONDS`, `HEALTH_CHECK_TIMEOUT_SECONDS` | `600`, `600`, `180` | Per-stage limits, all within `DEPLOYMENT_TIMEOUT_SECONDS` (1800) |
| `DOCKER_BUILD_PLATFORM` | (empty) | e.g. `linux/amd64` to force x86 images. Empty: build for the host; the worker reads the image architecture and sets the Fargate `runtimePlatform` to match (ARM64 on Apple Silicon) |

The worker validates all of this at startup and refuses to start with a list of every problem
(e.g. `AWS_ECS_MEMORY 256 is not valid for 256 CPU units on Fargate …`).

### Run it

```bash
set -a; source .env; set +a
make infra-up
make api          # terminal 1
make worker       # terminal 2: logs "AWS deployment enabled: account …, region us-east-1, cluster edgedeploy"
make web          # terminal 3
```

The worker never logs credentials, environment variable values, registry passwords or authorization headers; the
ECR password is written only to a private, per-push Docker config directory that is deleted after the push.

## End-to-end demo

1. Push `test-fixtures/sample-vite-app` to a GitHub repository of yours (see its README).
2. http://localhost:3000 → **Continue with GitHub** → **New project** → choose the repository → **Create project**.
3. Optional: **Settings → Environment variables** → add `GREETING=hello`. The value is encrypted in Postgres,
   written to SSM as a SecureString on deploy and never shown again.
4. **Deploy**. The deployment page walks through:

   | Progress | Timeline | What happens |
   |----------|----------|--------------|
   | Queued | Deployment queued | API stored the deployment and the outbox event |
   | Building | Repository cloned · Commit verified · Framework detected · Docker image built | as in Phase 3 |
   | Pushing image | Image pushed to ECR | repository ensured, `docker push`, digest resolved |
   | Deploying | Deployed to ECS | log group, SSM parameters, task definition revision (image pinned by digest), target group, listener, service create/update, wait for the rollout to complete |
   | Health check | Health check passed | ALB reports the task healthy, then `GET http://<alb-dns>:<port>/` returns 2xx/3xx |
   | Running | Deployment successful | previous deployment marked STOPPED ("Replaced by deployment #N") |

   The first deployment of a project typically takes 3–5 minutes (most of it is the Fargate task start and ALB health checks).
5. Click **Open Application**: `http://edgedeploy-alb-123456789.us-east-1.elb.amazonaws.com:10000` serves the app
   (budget mode: `http://<task-ip>:3000` or `:8080`).
   The project page shows the same URL as its production URL.
6. Deploy again (e.g. after a new commit): the same service, listener and URL get a new task definition revision;
   ECS replaces the task without downtime.

Inspecting it from the CLI:

```bash
PROJECT=<projectId from the URL>
aws ecs describe-services --cluster edgedeploy --services edgedeploy-$PROJECT \
  --query 'services[0].{status:status,running:runningCount,taskDefinition:taskDefinition,rollout:deployments[0].rolloutState}'
aws logs tail /edgedeploy/$PROJECT --follow       # the application's stdout/stderr
aws ecr describe-images --repository-name edgedeploy/$PROJECT --query 'imageDetails[].[imageTags[0],imageDigest]'
```

Rollback data: every deployment row stores the commit, image URI and digest, task definition ARN, cluster and
service. Redeploying an older deployment rebuilds that exact commit.

### Failure behaviour

| What goes wrong | Status | What you see |
|-----------------|--------|--------------|
| Expired/invalid credentials, missing permission | FAILED immediately (not retried) | "The worker's AWS credentials are invalid or expired (…)" / "The EdgeDeploy worker is not authorized to perform ecs:CreateService. Check the worker's IAM policy" (ARNs and account ids are stripped from user-facing messages) |
| Throttling, 5xx | retried up to `AWS_MAX_ATTEMPTS` with backoff | nothing, unless retries run out |
| Push fails | FAILED at *Image push* | registry error; push itself is retried up to 3 times |
| App crashes / exits | FAILED at *ECS deployment* | the task's stop reason, e.g. `Essential container in task exited (exit code 1)`; see `aws logs tail` |
| App does not answer on the health path | FAILED at *Health check* | target health reason or the HTTP status received |
| All 50 listener ports in use | FAILED at *ECS deployment* | "No free load balancer port between 10000 and 10049…" |
| Rollout or health check fails after the service was updated | FAILED; the ECS circuit breaker rolls back, and the worker also restores the previous RUNNING task definition (or scales a brand-new service to 0) so a broken release never keeps serving | the failure reason, e.g. "ECS rolled back to the previous version: the new tasks did not become healthy"; the restore is logged |
| A newer deployment of the same project already went out first | the older one is STOPPED instead of downgrading the project | "Skipped: deployment #N of this project is newer and already rolled out" |

Cancelling is possible while Queued, Building or Pushing; once the ECS rollout has started the deployment must run
to completion or failure (the API returns 409).

## 13. Cleanup

**Do this when you are done**, or the ALB keeps billing.

```bash
export AWS_REGION=us-east-1          # your admin credentials, not the worker's
scripts/aws-cleanup.sh                     # dry run: lists everything it would delete
scripts/aws-cleanup.sh --yes               # deletes per-project resources (services, task definitions, listeners,
                                           #   target groups, ECR repositories + images, log groups, SSM parameters)
scripts/aws-cleanup.sh --yes --delete-stack   # ...and the foundation stack (ALB, cluster, VPC, roles, policy)
```

Deleting a project in the dashboard does **not** delete its AWS resources yet; use the script.

Manual equivalent, in dependency order: scale each `edgedeploy-*` ECS service to 0 and delete it → deregister the
`edgedeploy-*` task definitions → delete listeners on ports 10000–10049 → delete `ed-*` target groups → delete
`edgedeploy/*` ECR repositories (`--force`) → delete `/edgedeploy/*` log groups → delete `/edgedeploy/*` SSM
parameters → `aws cloudformation delete-stack --stack-name edgedeploy`. If you created an IAM user or access key for
the worker, delete them too.

Afterwards, verify in the Billing console (Cost Explorer, grouped by service) over the next day that charges stop.

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| Worker refuses to start: `AWS credentials belong to account … but AWS_ACCOUNT_ID is …` | Wrong `AWS_PROFILE`, or `AWS_ACCOUNT_ID` typo |
| `Unable to assume the service linked role` on the first deployment | Run the `create-service-linked-role` command in §1 and redeploy |
| `CannotPullContainerError` in the stop reason | Tasks need a route to ECR: public subnets with `AWS_ECS_ASSIGN_PUBLIC_IP=true` (as in the template), or private subnets with a NAT gateway / VPC endpoints |
| `exec format error` | Image architecture mismatch; the worker sets `runtimePlatform` from the built image, so this means a custom base image for the wrong platform. Set `DOCKER_BUILD_PLATFORM=linux/amd64` |
| Health check fails but the app runs | The app must listen on `0.0.0.0:$PORT` and answer `APP_HEALTH_CHECK_PATH` with 2xx/3xx |
| URL times out | Your IP is not in `AllowedIngressCidr`; update the stack parameter |


[profile edgedeploy-worker]
role_arn = 458090377013
source_profile = <your admin profile from step 1>
region = us-east-1
[profile edgedeploy-worker]
role_arn = arn:aws:iam::458090377013:role/YOUR_ROLE_NAME
source_profile = default
region = ap-south-1