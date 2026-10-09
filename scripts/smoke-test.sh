#!/usr/bin/env bash
# End-to-end Phase 3 check against a running stack: create a project from a real GitHub repository,
# queue a deployment, and wait for the worker to build its Docker image (IMAGE_BUILT).
#
# Authentication is a browser session, so borrow one:
#   1. Sign in at http://localhost:3000
#   2. DevTools -> Application -> Cookies -> http://localhost:8080 -> copy EDGEDEPLOY_SESSION
#
#   EDGEDEPLOY_SESSION=... SMOKE_REPOSITORY=you/some-repo ./scripts/smoke-test.sh
set -euo pipefail

API="${API_URL:-http://localhost:8080}"
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-600}"
: "${EDGEDEPLOY_SESSION:?Set EDGEDEPLOY_SESSION to your session cookie (see header)}"
: "${SMOKE_REPOSITORY:?Set SMOKE_REPOSITORY to owner/repo you can push to}"
BRANCH="${SMOKE_BRANCH:-main}"

jar="$(mktemp)"
trap 'rm -f "$jar"' EXIT
printf 'localhost\tFALSE\t/\tFALSE\t0\tEDGEDEPLOY_SESSION\t%s\n' "$EDGEDEPLOY_SESSION" > "$jar"

json_field() { # json_field <field> : first string value of "field" in stdin
  grep -o "\"$1\":\"[^\"]*\"" | head -1 | cut -d'"' -f4
}
call() { curl -fsS -b "$jar" -c "$jar" "$@"; }

echo "→ Session"
login="$(call "$API/api/auth/me" | json_field login)" || { echo "✗ Not signed in (session expired?)"; exit 1; }
echo "  signed in as $login"

echo "→ CSRF token"
call -o /dev/null "$API/api/auth/csrf"
xsrf="$(awk '$6 == "XSRF-TOKEN" { print $7 }' "$jar")"

echo "→ Creating project for $SMOKE_REPOSITORY@$BRANCH"
project_id="$(call -X POST "$API/api/projects" -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $xsrf" \
  -d "{\"name\":\"smoke-$(date +%s)\",\"repository\":\"$SMOKE_REPOSITORY\",\"branch\":\"$BRANCH\"}" | json_field id)"
echo "  project $project_id"

echo "→ Deploying"
deployment="$(call -X POST "$API/api/projects/$project_id/deployments" -H "X-XSRF-TOKEN: $xsrf")"
deployment_id="$(echo "$deployment" | json_field id)"
echo "  deployment $deployment_id status=$(echo "$deployment" | json_field status) commit=$(echo "$deployment" | json_field commitSha)"

echo "→ Waiting for the build (timeout ${TIMEOUT_SECONDS}s)"
last=""
for _ in $(seq "$TIMEOUT_SECONDS"); do
  body="$(call "$API/api/deployments/$deployment_id")"
  status="$(echo "$body" | json_field status)"
  [[ "$status" != "$last" ]] && echo "  status: $status" && last="$status"
  case "$status" in
    IMAGE_BUILT) echo "✓ Image built: $(echo "$body" | json_field imageUri)"; exit 0 ;;
    FAILED|STOPPED) echo "✗ Deployment ended in $status: $(echo "$body" | json_field errorMessage)"; exit 1 ;;
  esac
  sleep 1
done
echo "✗ Timed out in status $last. Is the worker running, with Docker available?"
exit 1
