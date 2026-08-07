# Staging Valkey Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a password-protected Valkey 8.1 staging container to the existing EC2 Docker Compose deployment without changing the current stateless JWT application behavior.

**Architecture:** Valkey runs as an internal-only service on the existing `app` Docker network. Its data is stored in a named volume, AOF is enabled, and memory pressure uses `noeviction` so authentication state is not silently removed. Spring Data Redis and Refresh Token behavior remain out of scope for #141 and will be implemented by #140.

**Tech Stack:** Docker Compose, `valkey/valkey:8.1-alpine`, Valkey AOF, Docker healthcheck, Spring Boot environment variable placeholders, Markdown deployment documentation.

## Global Constraints

- Do not add Spring Data Redis, Lettuce, Refresh Token code, or Valkey failure-closed application logic.
- Do not expose host port `6379`.
- Keep the existing MySQL, backend, Nginx addresses and health behavior unchanged.
- Keep secrets only in EC2 `/opt/miriyum/.env`; never add real values to GitHub or the repository.
- Do not stage the unrelated `.idea/` directory or the stashed CloudWatch script change.
- Do not commit or push until the user explicitly authorizes it.

---

### Task 1: Add the staging Valkey service

**Files:**
- Modify: `deploy/docker-compose.prod.yml`
- Modify: `deploy/.env.example`

**Interfaces:**
- Produces the Compose service `valkey` on the existing `app` network.
- Produces `MIRIYUM_VALKEY_HOST`, `MIRIYUM_VALKEY_PORT`, and `MIRIYUM_VALKEY_PASSWORD` runtime variables for future #140 integration.

- [ ] **Step 1: Write the configuration expectation**

Verify the expected configuration before editing:

```powershell
docker compose --env-file deploy/.env.example -f deploy/docker-compose.prod.yml config
```

Expected: the command currently succeeds and the rendered services contain only `mysql`, `backend`, and `nginx`; no `valkey` service exists yet.

- [ ] **Step 2: Add the Valkey service and runtime variables**

Add `valkey/valkey:8.1-alpine` with:

- internal address `172.29.81.12`
- no `ports` section
- `valkey-data:/data`
- AOF enabled with `appendfsync everysec`
- `maxmemory 128mb`
- `maxmemory-policy noeviction`
- password supplied from `MIRIYUM_VALKEY_PASSWORD`
- authenticated `PONG` healthcheck
- `restart: unless-stopped`

Add the three Valkey variables to `deploy/.env.example` using placeholders, and pass the host, port, and password to the backend container without adding a Spring binding or Redis client dependency.

- [ ] **Step 3: Run Compose rendering validation**

Run:

```powershell
docker compose --env-file deploy/.env.example -f deploy/docker-compose.prod.yml config --quiet
docker compose --env-file deploy/.env.example -f deploy/docker-compose.prod.yml config | Select-String -Pattern 'valkey|172.29.81.12|6379|noeviction|128mb'
```

Expected: exit code `0`; the rendered configuration contains the Valkey service, internal address, password command, memory policy, and no host port mapping for `6379`.

### Task 2: Align deployment documentation

**Files:**
- Modify: `docs/deployment/docker-ecr-ssm-cd.md`
- Review: `docs/superpowers/specs/2026-08-07-staging-valkey-design.md`

**Interfaces:**
- Documents the staging-only Valkey infrastructure boundary.
- Keeps frontend, S3, RDS, ECS, ALB, TLS, and production infrastructure excluded.

- [ ] **Step 1: Replace the stale deployment scope statement**

Change the deployment document so it says Valkey infrastructure is included in staging, but the backend does not consume it until #140. Keep the statement that no Refresh Token behavior is changed by #141.

- [ ] **Step 2: Document the EC2 runtime setup**

Record that the operator must add a unique `MIRIYUM_VALKEY_PASSWORD` to `/opt/miriyum/.env`, keep the file mode at `600`, and never expose `6379` through the security group or Compose host ports.

- [ ] **Step 3: Verify documentation consistency**

Run:

```powershell
rg -n "Valkey|6379|Refresh Token|Spring Data Redis|#140|#141" deploy/docker-compose.prod.yml deploy/.env.example docs/deployment/docker-ecr-ssm-cd.md docs/superpowers/specs/2026-08-07-staging-valkey-design.md
```

Expected: #141 is described as infrastructure preparation, #140 owns application integration, and no document claims that the current backend already stores Refresh Token state in Valkey.

### Task 3: Run the focused verification

**Files:**
- Verify: `deploy/docker-compose.prod.yml`
- Verify: `deploy/.env.example`

- [ ] **Step 1: Validate the Compose model**

Run:

```powershell
docker compose --env-file deploy/.env.example -f deploy/docker-compose.prod.yml config --quiet
```

Expected: `0` and no validation error.

- [ ] **Step 2: Validate host exposure from rendered Compose**

Run:

```powershell
$rendered = (docker compose --env-file deploy/.env.example -f deploy/docker-compose.prod.yml config) -join "`n"
if ($rendered -match '(?m)^\s*-\s*"?6379:6379') { throw 'Valkey host port must not be exposed' }
if ($rendered -notmatch 'valkey/valkey:8\.1-alpine') { throw 'Valkey image is missing' }
```

Expected: the command exits successfully.

- [ ] **Step 3: Report runtime verification for EC2**

After the PR is merged and staging CD completes, run on EC2:

```bash
cd /opt/miriyum
sudo docker compose --env-file .env -f docker-compose.prod.yml ps
sudo docker compose --env-file .env -f docker-compose.prod.yml exec -T valkey sh -c 'REDISCLI_AUTH="$MIRIYUM_VALKEY_PASSWORD" valkey-cli ping'
sudo docker port miriyum-valkey-1
```

Expected: Valkey is `healthy`, the ping returns `PONG`, and the final command prints no host port mapping. If the generated container name differs, use `sudo docker compose ... ps -q valkey` to inspect the actual container.

### Task 4: Review the change boundary before integration

- [ ] **Step 1: Inspect the diff**

Run:

```powershell
git diff --stat
git diff -- deploy/docker-compose.prod.yml deploy/.env.example docs/deployment/docker-ecr-ssm-cd.md docs/superpowers/specs/2026-08-07-staging-valkey-design.md docs/superpowers/plans/2026-08-07-staging-valkey.md
```

Expected: only the Valkey infrastructure, its documentation, and the design/plan files are changed. `backend/build.gradle.kts`, Refresh Token Java code, and `.idea/` are absent from the diff.

- [ ] **Step 2: Stop before commit**

Show the user the focused diff and Compose verification output. Commit, push, and PR creation require a separate explicit authorization.
