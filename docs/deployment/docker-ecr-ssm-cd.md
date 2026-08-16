# Docker ECR SSM backend CD (Staging)

Issue: [#120](https://github.com/sparta-spring4/Commerce-Final-Project-MiriYum/issues/120)

## Scope

This is a staging API pre-deployment route, not the first MVP's final user deployment. It runs the Spring Boot API, MySQL, Nginx, and the Valkey staging container on one ARM64 staging EC2 instance. Nginx only proxies `/api/`; it deliberately returns `404` for `/` and `/actuator/`. No frontend asset, Vite server, S3, RDS, ECS, ALB, TLS certificate, or domain is configured by this change. The Valkey container is infrastructure preparation only; the backend does not consume it until the Refresh Token work in #140.

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

These are staging Environment variables, not application secrets. Application and database secrets remain only in the staging EC2's `/opt/miriyum/.env`.

## EC2 runtime setup

1. Copy `deploy/.env.example` to `/opt/miriyum/.env` without committing the copied file.
2. Replace every `replace-with-...` value with a unique staging value, including `MIRIYUM_VALKEY_PASSWORD` and `MIRIYUM_NOTIFICATION_HISTORY_CURSOR_SECRET`.
3. Run `chmod 600 /opt/miriyum/.env`.
4. Confirm the instance role has `AmazonEC2ContainerRegistryReadOnly` and Systems Manager access.
5. Confirm the security group allows TCP `80` only as required for the API. Do not expose MySQL `3306`, backend `8080`, or Valkey `6379`.

Before logging in to ECR or restarting containers, `deploy.sh` runs `docker compose config --quiet` with the server-local `.env`. A missing required key therefore stops the deployment before image pull and container replacement. Compose prints only the missing key name; the workflow and deployment script never print secret values or create the `.env` file.

The #141 infrastructure stage starts and health-checks the password-protected Valkey service. Validation includes staging `healthy`, unauthenticated `NOAUTH`, authenticated `PONG`, and no host port exposure. Valkey joins only the internal `backend-valkey` Docker network shared with the backend container; MySQL and Nginx cannot connect to it.

After #140 is deployed, Access JWT validation remains stateless, while Refresh Token login, rotation, revocation, reuse detection, and failure-closed authentication use Valkey through Spring Data Redis/Lettuce. Compose waits for MySQL health before starting the backend, but does not wait for Valkey health. If Valkey is unavailable, the backend still starts and deployment health remains available; only Refresh Token operations fail closed with `503`. Existing Access JWT requests and public endpoints continue without Valkey. The `MIRIYUM_VALKEY_HOST`, `MIRIYUM_VALKEY_PORT`, and `MIRIYUM_VALKEY_PASSWORD` values in the EC2 `.env` must match the internal `valkey` service; port `6379` remains private to the Docker network.

After the first staging deployment that includes Valkey, verify the service from the EC2 instance:

```bash
cd /opt/miriyum
sudo docker compose --env-file .env -f docker-compose.prod.yml ps valkey
sudo docker compose --env-file .env -f docker-compose.prod.yml exec -T valkey valkey-cli ping
sudo docker compose --env-file .env -f docker-compose.prod.yml exec -T valkey sh -ec 'REDISCLI_AUTH="$MIRIYUM_VALKEY_PASSWORD" valkey-cli ping'
sudo docker compose --env-file .env -f docker-compose.prod.yml port valkey 6379
```

The expected result is `healthy`, unauthenticated `NOAUTH Authentication required.`, then authenticated `PONG`; the final command must not print a host port. Record the deployment run and these results before manually closing #141.

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

The workflow uses the separate `staging-backend` marker when deciding the last successful backend image. This is intentionally separate from the shared `staging` Environment so a future frontend CD cannot make a backend deployment look newer. It also makes manual rollback explicit: the selected `inputs.image_tag` is the SHA recorded by the marker. After each deployment, record the GitHub Actions run URL, ECR image digest, SSM command ID, and EC2 loopback health result. Until those four runtime results exist, deployment evidence remains `NOT RUN`.
