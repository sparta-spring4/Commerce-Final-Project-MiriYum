# Docker ECR SSM backend CD (Staging)

Issue: [#120](https://github.com/sparta-spring4/Commerce-Final-Project-MiriYum/issues/120)

## Scope

This is the same-origin staging deployment route. It runs the version-matched React production
build, Spring Boot API, MySQL, gateway Nginx, and Valkey on one ARM64 staging EC2 instance.
`staging.miriyum.click` serves the frontend at `/` and proxies `/api/` to Spring Boot;
`staging-api.miriyum.click` remains API-only and deliberately returns `404` for `/` and
`/actuator/`. The frontend image is built from the same dev SHA as the backend image and is tagged
`<SHA>-frontend` in the existing staging ECR repository. Neither the frontend container nor the
backend, MySQL, or Valkey receives a host port. S3, RDS, ECS, ALB, and production infrastructure
are not part of this staging route.

The later frontend delivery must add static frontend assets to Nginx and retain the `/api/` proxy route. That work needs its own issue, review, and deploy verification before this can be called a same-origin user release.

This workflow is deliberately limited to the `staging` GitHub Environment and deploys only successful `dev` commits. Production is not configured here. A future production route must use a separate production account or clearly isolated resources, EC2 instance, IAM role, GitHub Environment with required reviewers, and a `main`-only workflow.

## Flow

1. `Backend CI` succeeds on a push to `dev`. Its required-check-compatible `backend-ci` aggregate succeeds only after the parallel `unit-test`, `integration-test-a`, `integration-test-b` jobs, plus the CD workflow contract check, succeed. CI results from `pull_request` events and forks are not deployment inputs.
2. For automatic deployment, `Backend CD (Staging)` reads the current remote `dev` HEAD before it receives OIDC credentials. If it differs from the successful CI SHA, the run is stale and the deploy job is skipped.
3. `Backend CD (Staging)` checks out that exact current successful commit and builds `linux/arm64` from `backend/Dockerfile`.
4. The image is pushed to private ECR with only the full 40-character Git SHA tag.
5. After the image is available, the workflow records a backend-only `staging-backend` deployment marker whose SHA is the image tag selected for deployment.
6. GitHub Actions sends the compose file, Nginx configuration, and deploy script through SSM to `/opt/miriyum`.
7. The EC2 script logs in to ECR, pulls the immutable image, runs Docker Compose, and checks `http://127.0.0.1:8080/actuator/health`. The marker is marked `success` or `failure` from the SSM result.

The runtime `.env` is created manually on EC2 and remains server-local. The CD workflow never creates it, uploads it, or writes its values to GitHub Actions logs.

Manual dispatch is reserved for rollback or redeployment of an image that already exists in ECR. Enter only an existing 40-character Git SHA tag. The workflow stops before SSM if that image cannot be found, so a typo cannot reach the server as a failed `docker pull`.

## One-time AWS setup

1. Configure a GitHub OIDC provider with issuer `https://token.actions.githubusercontent.com` and audience `sts.amazonaws.com`.
2. Create `miriyum-github-staging-cd-role` and restrict its trust policy subject to `repo:sparta-spring4/Commerce-Final-Project-MiriYum:environment:staging`. The `staging` Environment branch policy and the workflow job condition separately restrict this Environment to successful original-repository `dev` pushes.
3. Grant the role only ECR push access and `ecr:BatchGetImage` on `miriyum-backend`, plus SSM command/invocation access to the staging EC2 instance using `AWS-RunShellScript`. `ecr:BatchGetImage` lets a rerun reuse an existing immutable SHA tag after a later SSM failure. Do not reuse this role for a future production instance.
4. In repository Settings, Environments, create `staging`. Set its deployment branches to only `dev`; do not configure required reviewers because this is an integration-test environment. Register these variables in the `staging` Environment, not as repository-wide variables:

| Variable | Value |
|---|---|
| `AWS_REGION` | `ap-northeast-2` |
| `AWS_ECR_REPOSITORY` | `miriyum-backend` |
| `AWS_EC2_INSTANCE_ID` | The staging EC2 instance ID |
| `AWS_ROLE_TO_ASSUME` | The IAM role ARN for `miriyum-github-staging-cd-role` |
| `MIRIYUM_PORTONE_STORE_ID` | The public Store ID for the approved staging PortOne store |
| `MIRIYUM_PORTONE_CHANNEL_KEY` | The public Channel Key for the approved staging PortOne channel |

These are staging Environment variables, not application secrets. The workflow passes only the two
public PortOne values to the Frontend image build. Never register `MIRIYUM_PORTONE_API_SECRET` as a
GitHub Variable or Secret, and never add it to Frontend build arguments. Runtime copies of
application and database secrets remain only in the staging EC2's `/opt/miriyum/.env`; the
authoritative PortOne API Secret remains in staging AWS Secrets Manager.

## EC2 runtime setup

1. Copy `deploy/.env.example` to `/opt/miriyum/.env` without committing the copied file.
2. Replace every `replace-with-...` value with a unique staging value, including `MIRIYUM_VALKEY_PASSWORD`, `MIRIYUM_NOTIFICATION_HISTORY_CURSOR_SECRET`, `MIRIYUM_WAITING_HISTORY_CURSOR_SECRET`, and `MIRIYUM_QR_STORAGE_GENERATION`. The waiting-history cursor secret must be a dedicated random value of at least 32 characters and remain the same across ordinary backend replacements; rotating it invalidates cursors issued with the previous value. The QR storage generation must match `^[A-Za-z0-9._-]{1,64}$` on every backend instance. Set `MIRIYUM_STORE_GEOCODING_REST_API_KEY` to the Kakao Local REST API key used for store address verification; do not reuse the Kakao OAuth key.
3. Run `chmod 600 /opt/miriyum/.env`.
4. Confirm the instance role has `AmazonEC2ContainerRegistryReadOnly` and Systems Manager access.
5. Confirm the security group allows TCP `80` only as required for the API. Do not expose MySQL `3306`, backend `8080`, or Valkey `6379`.

### PortOne staging configuration boundary (#520)

| Value | Authoritative source | Delivery target |
|---|---|---|
| `MIRIYUM_PORTONE_STORE_ID` | Approved staging PortOne public configuration | GitHub `staging` Environment Variable for the Frontend build and the server-local `/opt/miriyum/.env` for the Backend container |
| `MIRIYUM_PORTONE_CHANNEL_KEY` | Approved staging PortOne public configuration | GitHub `staging` Environment Variable for the Frontend build only |
| `MIRIYUM_PORTONE_API_SECRET` | Staging AWS Secrets Manager | Server-local `/opt/miriyum/.env`, then the Backend container only |

The deployment operator owns materialization of `MIRIYUM_PORTONE_API_SECRET`. In an approved
operator session, retrieve it with an IAM role restricted to the staging secret and write it into
`/opt/miriyum/.env` without displaying the value. Keep shell tracing disabled, do not use a GitHub
Actions or SSM command as the transfer channel, and do not retain the value in command output,
temporary artifacts, Issues, or PRs. If the approved procedure uses a temporary file, create it with
mode `600` and remove it immediately after updating the environment file. Restore `chmod 600
/opt/miriyum/.env` after every edit.

The Store ID in `/opt/miriyum/.env` must match the public Store ID registered in the GitHub
`staging` Environment. The Channel Key is not a Backend or Compose setting. The API Secret and
API Secret is not a Frontend setting and is never part of a static image. These three values only
establish the delivery contract: they do not set `MIRIYUM_PAYMENT_ENABLED`, enable a payment worker,
or prove a staging payment. Keep payment disabled when any required PortOne value or the separate
activation prerequisites are not configured and verified.

### S3 runtime activation gate (#223)

S3 runtime activation is a separate deployment step. Do not set
`MIRIYUM_STORAGE_S3_ENABLED=true` merely because the application image is
deployed. Keep both `MIRIYUM_STORAGE_S3_ENABLED` and
`MIRIYUM_STORAGE_S3_RECONCILIATION_ENABLED` set to `false` until the following
checks pass in the same staging environment.

**Preflight**

- The bucket name and region are present in the server-local `.env`. The
  bucket is private, public access is blocked, and versioning is disabled.
- Before activation, record a non-sensitive pass/fail result that bucket
  default encryption is enabled, the bucket policy denies non-TLS requests,
  and the approved object lifecycle and retention policy exists. Do not enable
either flag while any of these settings is undecided or absent.

Production ECS CD preserves the current two flags and treats an absent flag as
`false`; it must not activate S3 merely because a new backend image is deployed.
The production bucket lifecycle only aborts incomplete multipart uploads. Normal
public image retention and deletion remain owned by FileMetadata reconciliation
until #223 staging smoke has approved activation.
- The instance role has only the required access to this bucket and cannot
  access unrelated buckets. Do not copy bucket names, ARNs, secrets, or object
  keys into Issues, PRs, or workflow logs.
- The deployed image contains the matching Flyway schema and the reconciliation
  migration completed successfully.
- The current deployment is healthy with both flags disabled. Record the exact
  full SHA before changing the flags so the activation can be rolled back to
  that same image.

**Activation and smoke**

1. Set both flags to `true` in the server-local `.env` and redeploy the same
   approved full SHA. Do not enable the worker while the S3 runtime is disabled.
2. Confirm loopback health is `UP`, the reconciliation scheduler is registered,
   and startup logs contain no missing bucket, region, or permission error.
3. As an authorized staging store operator, upload one supported image, replace
   it, and delete it. Confirm the public image changes only after metadata is
   `CONFIRMED`, and that an unauthorized request is rejected.
4. Confirm reconciliation success, retryable failure, and long-stay observations
   contain aggregate counts only. Do not capture tokens, cookies, source
   filenames, object keys, user IDs, or raw provider errors.

**Reconciliation fault smoke**

1. Use a new synthetic staging store and a generated test-only image. Do not
   use an existing user, store, menu, object, or business-registration record.
2. After a successful upload creates the synthetic object, apply the
   pre-approved staging-only fault that denies `DeleteObject` only for that
   generated test object's prefix. Do not broaden the denial to production
   prefixes, the whole bucket, or unrelated actions.
3. Delete the synthetic image through the normal authorized API. Separately
   confirm the external `503 COMMON_012` response and the internal `DELETED`
   retry target with its aggregate counter; neither observation may expose an
   object key, file ID, user ID, token, or provider error.
4. Keep the fault in place and run the reconciliation worker at least once.
   Confirm the aggregate `failed` count increases and the synthetic target is
   scheduled for retry. Do not remove the fault before this failed worker path
   is observed.
5. Remove the fault, wait until `nextAttemptAt`, and confirm the first eligible
   worker execution converges the synthetic metadata and object cleanup. Confirm
   the aggregate `failed` count does not increase again. If it does not
   converge within the approved observation window, stop the smoke and follow
   rollback.
6. For a separately approved long-stay fixture, keep the same narrowly scoped
   fault only until the configured long-stay threshold is crossed. Confirm the
   long-stay observation is an aggregate count, then remove the fault and wait
   for convergence. Record only the run URL, full SHA, aggregate counters, and
   success/failure result.
7. Remove the synthetic store and verify no temporary deny rule remains. Stop
   immediately and roll back if the fault affects any non-synthetic object or
   the cleanup worker reports an unexpected error.

**Rollback**

If any smoke, permission, health, or reconciliation check fails, set both flags
back to `false` and redeploy the same approved SHA. Confirm the service is
healthy and that new image requests fail closed without deleting the last
confirmed public image. Preserve only the run URL, full SHA, health result, and
aggregate observation outcome; never use ad hoc bucket deletion as rollback.

### Store geocoding secret migration

The staging Compose file forwards `MIRIYUM_STORE_GEOCODING_REST_API_KEY` only to the backend container. An empty value keeps the existing fail-closed `503 COMMON_012` behavior for store registration, so a configured value is a prerequisite for staging store-registration smoke and k6 fixture preparation.

Migrate without recording the value in Git, Actions logs, SSM parameters, or deployment artifacts:

1. Add `MIRIYUM_STORE_GEOCODING_REST_API_KEY` to the server-local `/opt/miriyum/.env` before deploying the Compose revision that consumes it. Do not rely on `MIRIYUM_KAKAO_LOCAL_REST_API_KEY` as a staging fallback: staging Compose has never forwarded that legacy name to the backend, so retaining it in the host `.env` does not create a rollback window. The temporary legacy overlap documented for production ECS applies only to that environment.
2. Deploy the compatibility revision whose application configuration prefers the canonical name and retains the legacy name only as a fallback.
3. Create one synthetic staging store through the public API and verify successful geocoding without printing the key, request authorization header, provider response body, or precise fixture coordinates in logs or artifacts.
4. After the same canonical-name evidence exists for production ECS, remove the legacy value from the environment. Remove the application fallback only in a later reviewed change after both environments no longer depend on it.

This migration configures the deployment boundary; it does not by itself prove the staging smoke or baseline in #357.

### Temporary staging load-test rate-limit exception

The staging Compose file fixes `MIRIYUM_RUNTIME_ENVIRONMENT=staging`; operators cannot change that marker through the server-local `.env`. `MIRIYUM_STAGING_LOAD_TEST_SOURCE_IP` is empty by default, and the backend permits an exception only for one valid public IPv4 address and only for login and token-refresh rate-limit categories. Production does not receive the staging runtime marker, so setting only the source-IP variable there does not enable the exception. IPv6, CIDR, multiple values, hostnames, and non-public addresses are rejected when staging starts. The first request that actually receives the exception emits one process-local `event=staging_rate_limit_bypass_applied` warning with its category and without the source IP; repeated matching requests do not emit per-request warnings.

Use the exception only during the approved #357 window:

1. Confirm the approved full deployment SHA, execution time, load caps, synthetic fixture, and the runner's current public IPv4 address. Do not put the IP in an Issue, PR, chat transcript, shell command, or retained artifact.
2. Open `/opt/miriyum/.env` with an interactive privileged editor and set exactly one `MIRIYUM_STAGING_LOAD_TEST_SOURCE_IP` value. Keep file mode `600`; do not print or copy the file into Actions or SSM output.
3. Manually dispatch `Backend CD (Staging)` from `dev` with the same approved full SHA. Record only the deployment run URL, image digest, SSM command ID, and loopback health result.
4. Run the approved smoke before the baseline. Confirm the IP-free `event=staging_rate_limit_bypass_applied` warning appears once for the backend process without copying surrounding request data. Store only execution time, success/429/5xx counts, p50/p95/p99, scenario inputs, and the deployed full SHA. Never retain the source IP, tokens, cookies, authorization headers, or raw HTTP output.
5. Immediately after the run, clear the value with the interactive editor and manually redeploy the same approved SHA. Do this after success, failure, or an interrupted k6 run.
6. Confirm the backend container has an empty source-IP value without printing environment contents, then issue the normal login limit plus one request from the runner and confirm the final request returns `429`. Record only the recovery deployment and the `429` result.

If injection, deployment, smoke, cleanup, or recovery verification fails, stop #357. Do not continue a baseline while the exception state is unknown, and do not broaden the IP or rate-limit scope as a workaround.

Before logging in to ECR or restarting containers, `deploy.sh` runs `docker compose config --quiet` with the server-local `.env`. A missing required key therefore stops the deployment before image pull and container replacement. Compose prints only the missing key name; the workflow and deployment script never print secret values or create the `.env` file.

The #141 infrastructure stage starts and health-checks the password-protected Valkey service. Validation includes staging `healthy`, unauthenticated `NOAUTH`, authenticated `PONG`, and no host port exposure. Valkey joins only the internal `backend-valkey` Docker network shared with the backend container; MySQL and Nginx cannot connect to it.

Auth Valkey must use `maxmemory-policy noeviction`; eviction can remove TTL-free QR epoch hashes or Refresh security state and invalidate the atomic logout contract. The staging/production and standard local Compose commands set this explicitly. Any externally managed Valkey must return `noeviction` from `CONFIG GET maxmemory-policy` before rollout. The opt-in local load-test override is non-production and currently relies on Valkey's default `noeviction`, so verify its runtime value on every run instead of treating the file as an explicit guarantee.

After #140 is deployed, Access JWT validation remains stateless, while Refresh Token login, rotation, revocation, reuse detection, and failure-closed authentication use Valkey through Spring Data Redis/Lettuce. Compose waits for MySQL health before starting the backend, but does not wait for Valkey health. If Valkey is unavailable, the backend still starts and deployment health remains available; only Refresh Token operations fail closed with `503`. Existing Access JWT requests and public endpoints continue without Valkey. The `MIRIYUM_VALKEY_HOST`, `MIRIYUM_VALKEY_PORT`, and `MIRIYUM_VALKEY_PASSWORD` values in the EC2 `.env` must match the internal `valkey` service; port `6379` remains private to the Docker network.

`MIRIYUM_QR_STORAGE_GENERATION` fences account QR epochs from restored Valkey data. Keep the same value for ordinary backend or Valkey restarts. Before restoring any older Valkey snapshot, stop the backend, choose a value that has never been used in that environment, update every backend instance, restore the snapshot, and only then resume the backend. Never reopen an earlier generation value. A missing or invalid value leaves non-QR Access JWT traffic available but makes QR capture/check and a Refresh-authorized Consumer logout mutation fail closed with `COMMON_012`; it must not be treated as a successful server logout or QR revocation.

Alert on `event=qr_epoch_refresh_index_mismatch`. `expected=present` means an ACTIVE family was not found in its account index, while `expected=absent` means an exact completed logout marker still has a stale index member. The application log intentionally omits account, family, token, raw token, Valkey key, and generation values. With restricted Valkey access, inspect only the relevant key types, PTTL values, membership, and family status; preserve a snapshot or equivalent incident evidence, isolate the affected logout path if signals continue, and escalate to the Auth owner. Do not attempt ad hoc repair with standalone `SADD`, `DEL`, or `HSET`: cookie expiry can prevent a user retry while the server family remains ACTIVE, and non-atomic edits can create a second inconsistency. Resume normal mutation only through an owner-approved atomic repair procedure or a known-good recovery path.

After the first staging deployment that includes Valkey, verify the service from the EC2 instance:

```bash
cd /opt/miriyum
sudo docker compose --env-file .env -f docker-compose.prod.yml ps valkey
sudo docker compose --env-file .env -f docker-compose.prod.yml exec -T valkey valkey-cli ping
sudo docker compose --env-file .env -f docker-compose.prod.yml exec -T valkey sh -ec 'REDISCLI_AUTH="$MIRIYUM_VALKEY_PASSWORD" valkey-cli ping'
sudo docker compose --env-file .env -f docker-compose.prod.yml exec -T valkey sh -ec 'REDISCLI_AUTH="$MIRIYUM_VALKEY_PASSWORD" valkey-cli CONFIG GET maxmemory-policy'
sudo docker compose --env-file .env -f docker-compose.prod.yml port valkey 6379
```

The expected result is `healthy`, unauthenticated `NOAUTH Authentication required.`, authenticated `PONG`, and a `maxmemory-policy` value of `noeviction`; the final command must not print a host port. Record the deployment run and these results before manually closing #141.

## MySQL trigger migration recovery

The MySQL Compose command includes `--log-bin-trust-function-creators=1`, which configures MySQL's `log_bin_trust_function_creators` setting. When binary logging is enabled, this lets Flyway create audit triggers without requiring a privileged database account. The option is part of the Compose-managed MySQL startup command, so it is applied again whenever the staging MySQL container is recreated. Do not remove the option simply because a later migration succeeds.

If a Flyway migration that creates a trigger fails, first preserve the failure evidence and inspect the current state. Never automatically delete objects or modify Flyway history.

```bash
cd /opt/miriyum
sudo docker compose --env-file .env -f docker-compose.prod.yml ps mysql backend
sudo docker compose --env-file .env -f docker-compose.prod.yml logs --tail=200 backend
sudo docker compose --env-file .env -f docker-compose.prod.yml exec -T mysql sh -ec \
  'mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" -e "SELECT installed_rank, version, description, type, script, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 20;"'
sudo docker compose --env-file .env -f docker-compose.prod.yml exec -T mysql sh -ec \
  'mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" -e "SHOW TRIGGERS;"'
```

Use the failed migration script and backend log to identify the expected trigger, then compare its name and definition with the `SHOW TRIGGERS` result. Deleting a partial trigger or changing a failed `flyway_schema_history` row is a manual recovery decision: the Deploy/Platform owner must first confirm the staging backup, the expected migration source, and the existing object state. This runbook deliberately provides no automatic cleanup or Flyway repair command.

After that manual decision is complete, rerun the selected immutable SHA deployment. Re-run the four commands above, confirm the migration row is successful, confirm the intended trigger exists, and then verify `http://127.0.0.1:8080/actuator/health` is `UP` before recording the recovery as complete.

## Release and rollback

An ordinary push to `dev` deploys only to staging after `Backend CI` succeeds. The CD job additionally requires the triggering CI event to be a `push` from this repository, so a successful pull request CI result, including a fork PR, never receives OIDC or SSM deployment authority. Before automatic build and deployment, the workflow compares the completed CI SHA with the current remote `dev` HEAD and skips stale runs. A manual `Backend CD (Staging)` dispatch is allowed only from `dev` and accepts a full 40-character SHA tag; this is the only path that intentionally deploys a previous ECR image for staging rollback. The manual path checks out the same SHA before sending deployment files, so the Compose, Nginx, and deploy script revisions match the selected backend image.

If an image push succeeds but a later SSM deployment step fails, rerun the failed workflow instead of deleting or overwriting the immutable ECR tag. The workflow checks whether the same SHA tag already exists and reuses it, then retries only the remaining deployment path.

### Reservation deposit worker rollback

`MIRIYUM_RESERVATION_DEPOSIT_WORKER_ENABLED` is a startup-time switch for the reservation deposit process and refund workers. The staging Compose and production ECS templates set it to `true` for normal operation. Changing the value does not stop workers in containers or tasks that are already running.

The production CD workflow does not read `deploy/ecs/production-task-definition.json`. It copies the task definition currently attached to the ECS Service and changes only the backend image, so merging a repository template change does not add this setting to an existing production Service.

For the first production activation or an emergency claim stop, use an approved operator session to clone the live task definition and change only this environment value. Set the approved production identifiers without printing the current task definition or the rest of its environment. Use `WORKER_ENABLED=false` for the disabled revision and `WORKER_ENABLED=true` when re-enabling:

```bash
set -euo pipefail
umask 077

export AWS_REGION=ap-northeast-2
export ECS_CLUSTER=replace-with-production-cluster
export ECS_SERVICE=replace-with-production-service
export ECS_CONTAINER_NAME=backend
export WORKER_ENABLED=false

case "$WORKER_ENABLED" in
  true|false) ;;
  *) echo "WORKER_ENABLED must be true or false" >&2; exit 1 ;;
esac

current_task_definition=$(aws ecs describe-services \
  --cluster "$ECS_CLUSTER" \
  --services "$ECS_SERVICE" \
  --query 'services[0].taskDefinition' \
  --output text)

work_dir=$(mktemp -d)
cleanup() {
  rm -f -- \
    "$work_dir/current-task-definition.json" \
    "$work_dir/next-task-definition.json"
  rmdir -- "$work_dir"
}
trap cleanup EXIT

aws ecs describe-task-definition \
  --task-definition "$current_task_definition" \
  --query taskDefinition \
  --output json > "$work_dir/current-task-definition.json"

backend_count=$(jq --arg container "$ECS_CONTAINER_NAME" \
  '[.containerDefinitions[] | select(.name == $container)] | length' \
  "$work_dir/current-task-definition.json")
if [ "$backend_count" != "1" ]; then
  echo "Expected exactly one backend container" >&2
  exit 1
fi

jq --arg container "$ECS_CONTAINER_NAME" --arg enabled "$WORKER_ENABLED" '
  .containerDefinitions |= map(
    if .name == $container then
      .environment = (
        (.environment // []
          | map(select(.name != "MIRIYUM_RESERVATION_DEPOSIT_WORKER_ENABLED")))
        + [{"name": "MIRIYUM_RESERVATION_DEPOSIT_WORKER_ENABLED", "value": $enabled}]
      )
    else . end
  )
  | del(
      .taskDefinitionArn,
      .revision,
      .status,
      .requiresAttributes,
      .compatibilities,
      .registeredAt,
      .registeredBy,
      .deregisteredAt
    )
' "$work_dir/current-task-definition.json" > "$work_dir/next-task-definition.json"

worker_value_count=$(jq --arg container "$ECS_CONTAINER_NAME" --arg enabled "$WORKER_ENABLED" \
  '[.containerDefinitions[] | select(.name == $container)
    | .environment[] | select(.name == "MIRIYUM_RESERVATION_DEPOSIT_WORKER_ENABLED"
      and .value == $enabled)] | length' \
  "$work_dir/next-task-definition.json")
if [ "$worker_value_count" != "1" ]; then
  echo "Worker value was not rendered exactly once" >&2
  exit 1
fi

task_definition_arn=$(aws ecs register-task-definition \
  --cli-input-json "file://$work_dir/next-task-definition.json" \
  --query 'taskDefinition.taskDefinitionArn' \
  --output text)

aws ecs update-service \
  --cluster "$ECS_CLUSTER" \
  --service "$ECS_SERVICE" \
  --task-definition "$task_definition_arn" \
  --output json >/dev/null
aws ecs wait services-stable \
  --cluster "$ECS_CLUSTER" \
  --services "$ECS_SERVICE"

primary_task_definition=$(aws ecs describe-services \
  --cluster "$ECS_CLUSTER" \
  --services "$ECS_SERVICE" \
  --query 'services[0].deployments[?status==`PRIMARY`].taskDefinition | [0]' \
  --output text)
if [ "$primary_task_definition" != "$task_definition_arn" ]; then
  echo "The new task definition is not PRIMARY" >&2
  exit 1
fi

runtime_value=$(aws ecs describe-task-definition \
  --task-definition "$task_definition_arn" \
  --output json \
  | jq -r --arg container "$ECS_CONTAINER_NAME" \
      '.taskDefinition.containerDefinitions[] | select(.name == $container)
       | .environment[] | select(.name == "MIRIYUM_RESERVATION_DEPOSIT_WORKER_ENABLED")
       | .value')
if [ "$runtime_value" != "$WORKER_ENABLED" ]; then
  echo "The registered worker value does not match the requested value" >&2
  exit 1
fi

running_task_output=$(aws ecs list-tasks \
  --cluster "$ECS_CLUSTER" \
  --service-name "$ECS_SERVICE" \
  --desired-status RUNNING \
  --query 'taskArns[]' \
  --output text)
if [ -z "$running_task_output" ]; then
  echo "No running service task was available for drain verification" >&2
  exit 1
fi
read -r -a running_task_arns <<< "$running_task_output"

old_task_count=$(aws ecs describe-tasks \
  --cluster "$ECS_CLUSTER" \
  --tasks "${running_task_arns[@]}" \
  --output json \
  | jq --arg task_definition "$task_definition_arn" \
      '[.tasks[] | select(.taskDefinitionArn != $task_definition)] | length')
if [ "$old_task_count" != "0" ]; then
  echo "A task from the previous enabled revision is still running" >&2
  exit 1
fi
```

The temporary JSON files contain production configuration and resource identifiers even though they contain no secret values. The restrictive umask keeps them private, and the `EXIT` trap removes them after either success or failure. Record only the new task definition ARN, requested worker value, Service stability result, and old-task count.

Use this order when an image rollback must not start new reservation deposit claims:

1. Set `MIRIYUM_RESERVATION_DEPOSIT_WORKER_ENABLED=false` in the staging server-local `.env`, or register a production ECS task definition revision whose backend container has the value `false`.
2. Deploy that disabled revision before changing the image. Verify the replacement backend containers or tasks received `false` without printing the rest of their environment.
3. Wait until every previously enabled backend container or ECS task has stopped or drained. Do not declare new claims stopped while an enabled instance is still running.
4. Deploy the selected previous image while keeping the worker value `false`, then perform the environment's normal health verification.

Re-enabling the worker also requires a new container or task revision with the value set explicitly to `true`. Do not treat an environment-file or task-definition edit by itself as a runtime state change.

The workflow uses the separate `staging-backend` marker when deciding the last successful backend image. This is intentionally separate from the shared `staging` Environment so a future frontend CD cannot make a backend deployment look newer. It also makes manual rollback explicit: the selected `inputs.image_tag` is the SHA recorded by the marker. After each deployment, record the GitHub Actions run URL, ECR image digest, SSM command ID, and EC2 loopback health result. Until those four runtime results exist, deployment evidence remains `NOT RUN`.

## SSE deployment boundary

Backend CD transfers the shared Nginx SSE location snippet with the existing HTTP/HTTPS templates. The snippet applies only to the Notification consumer and Waiting consumer/store-operator stream routes, disables proxy buffering and caching, and leaves the generic `/api/` proxy unchanged. Application Runtime remains fail-closed when its dedicated cursor secret or bounded policy is invalid.

Environment activation, load evidence, Valkey interruption, same-SHA backend replacement and rollback must follow [the SSE Runtime runbook](sse-runtime-runbook.md). The loadtest values are local inputs, not staging or production defaults. Until the post-#455 production Compose/ECS secret contract and approved evidence are merged, keep production SSE disabled and record staging, browser, and production execution as `NOT RUN` rather than inferring success from template delivery.
