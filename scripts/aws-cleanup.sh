#!/usr/bin/env bash
# Removes everything EdgeDeploy created in AWS so it stops costing money.
#
# The worker creates per-project resources at deploy time (ECS services, task definitions, ALB listeners,
# target groups, ECR repositories, log groups, SSM parameters). They are not part of the CloudFormation
# stack, so delete them first; then (optionally) the foundation stack itself.
#
#   scripts/aws-cleanup.sh                          # dry run: only lists what would be deleted
#   scripts/aws-cleanup.sh --yes                    # deletes per-project resources
#   scripts/aws-cleanup.sh --yes --delete-stack     # ...and the foundation stack (VPC, ALB, cluster, roles)
#
# Uses the AWS CLI with your normal credentials (AWS_PROFILE / AWS_REGION). Names follow
# apps/worker/.../aws/AwsResourceNames; override with the variables below if you changed them.
set -euo pipefail

STACK_NAME="${STACK_NAME:-edgedeploy}"
CLUSTER="${AWS_ECS_CLUSTER:-edgedeploy}"
ECR_PREFIX="${AWS_ECR_REPOSITORY_PREFIX:-edgedeploy}"
LOG_PREFIX="${AWS_LOG_GROUP:-/edgedeploy}"
PORT_START="${AWS_ALB_LISTENER_PORT_START:-10000}"
PORT_END="${AWS_ALB_LISTENER_PORT_END:-10049}"

EXECUTE=false
DELETE_STACK=false
for arg in "$@"; do
  case "$arg" in
    --yes) EXECUTE=true ;;
    --delete-stack) DELETE_STACK=true ;;
    -h|--help) sed -n '2,16p' "$0"; exit 0 ;;
    *) echo "Unknown argument: $arg" >&2; exit 2 ;;
  esac
done

: "${AWS_REGION:?Set AWS_REGION (e.g. export AWS_REGION=us-east-1)}"
command -v aws >/dev/null || { echo "The AWS CLI is required" >&2; exit 1; }

run() {
  if $EXECUTE; then
    echo "+ $*"
    "$@" >/dev/null
  else
    echo "[dry run] $*"
  fi
}

account=$(aws sts get-caller-identity --query Account --output text)
echo "Account $account, region $AWS_REGION, cluster $CLUSTER, stack $STACK_NAME"
$EXECUTE || echo "Dry run: nothing is deleted. Re-run with --yes to delete."
echo

echo "== ECS services (scaled to 0, then deleted)"
deleted_services=()
services=$(aws ecs list-services --cluster "$CLUSTER" --query 'serviceArns[]' --output text 2>/dev/null || true)
for arn in $services; do
  name="${arn##*/}"
  [[ "$name" == edgedeploy-* ]] || continue
  run aws ecs update-service --cluster "$CLUSTER" --service "$name" --desired-count 0
  run aws ecs delete-service --cluster "$CLUSTER" --service "$name" --force
  deleted_services+=("$name")
done
if $EXECUTE && ((${#deleted_services[@]} > 0)); then
  echo "Waiting for services to drain (target groups and the cluster can only be deleted afterwards)..."
  aws ecs wait services-inactive --cluster "$CLUSTER" --services "${deleted_services[@]}"
fi

echo "== ECS task definitions (families edgedeploy-*: deregister, then delete)"
# list-task-definition-families takes a prefix; list-task-definitions takes an exact family name.
for family in $(aws ecs list-task-definition-families --family-prefix edgedeploy- --status ALL --query 'families[]' --output text); do
  revisions=()
  for td in $(aws ecs list-task-definitions --family-prefix "$family" --status ACTIVE --query 'taskDefinitionArns[]' --output text); do
    run aws ecs deregister-task-definition --task-definition "$td"
    revisions+=("$td")
  done
  for td in $(aws ecs list-task-definitions --family-prefix "$family" --status INACTIVE --query 'taskDefinitionArns[]' --output text); do
    [[ " ${revisions[*]-} " == *" $td "* ]] || revisions+=("$td")
  done
  # delete-task-definitions accepts at most 10 revisions per call.
  for ((i = 0; i < ${#revisions[@]}; i += 10)); do
    run aws ecs delete-task-definitions --task-definitions "${revisions[@]:i:10}"
  done
done

echo "== ALB listeners on ports $PORT_START-$PORT_END"
alb=$(aws cloudformation describe-stacks --stack-name "$STACK_NAME" \
  --query "Stacks[0].Outputs[?OutputKey=='AwsAlbArn'].OutputValue" --output text 2>/dev/null || true)
if [[ -n "$alb" && "$alb" != "None" ]]; then
  aws elbv2 describe-listeners --load-balancer-arn "$alb" --query 'Listeners[].[ListenerArn,Port]' --output text |
    while read -r listener port; do
      if ((port >= PORT_START && port <= PORT_END)); then
        run aws elbv2 delete-listener --listener-arn "$listener"
      fi
    done
else
  echo "(no load balancer in stack $STACK_NAME, e.g. budget mode: nothing to do)"
fi

echo "== Target groups (ed-*, tagged edgedeploy:managed)"
for tg in $(aws elbv2 describe-target-groups --query "TargetGroups[?starts_with(TargetGroupName, 'ed-')].TargetGroupArn" --output text); do
  managed=$(aws elbv2 describe-tags --resource-arns "$tg" \
    --query "TagDescriptions[0].Tags[?Key=='edgedeploy:managed'].Value" --output text)
  [[ "$managed" == "true" ]] && run aws elbv2 delete-target-group --target-group-arn "$tg"
done

echo "== ECR repositories ($ECR_PREFIX/*, including their images)"
for repo in $(aws ecr describe-repositories --query "repositories[?starts_with(repositoryName, '$ECR_PREFIX/')].repositoryName" --output text); do
  run aws ecr delete-repository --repository-name "$repo" --force
done

echo "== CloudWatch log groups ($LOG_PREFIX/*)"
for group in $(aws logs describe-log-groups --log-group-name-prefix "$LOG_PREFIX/" --query 'logGroups[].logGroupName' --output text); do
  run aws logs delete-log-group --log-group-name "$group"
done

echo "== SSM parameters (/edgedeploy/*)"
read -r -a params <<< "$(aws ssm get-parameters-by-path --path /edgedeploy --recursive --query 'Parameters[].Name' --output text)"
for ((i = 0; i < ${#params[@]}; i += 10)); do
  run aws ssm delete-parameters --names "${params[@]:i:10}"
done

if $DELETE_STACK; then
  echo "== CloudFormation stack $STACK_NAME (VPC, ALB, ECS cluster, IAM roles and policy)"
  run aws cloudformation delete-stack --stack-name "$STACK_NAME"
  if $EXECUTE; then
    echo "Waiting for the stack to be deleted..."
    aws cloudformation wait stack-delete-complete --stack-name "$STACK_NAME"
  fi
fi

echo
if $EXECUTE; then
  echo "Done. Check the Billing console (Bills / Cost Explorer) over the next day to confirm charges stopped."
else
  echo "Dry run complete. Re-run with --yes to delete the resources listed above."
fi
