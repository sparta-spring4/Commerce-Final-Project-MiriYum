# Docker ECR SSM backend CD (Staging)

Issue: [#120](https://github.com/sparta-spring4/Commerce-Final-Project-MiriYum/issues/120)

## Scope

This is a staging API pre-deployment route, not the first MVP's final user deployment. It runs the Spring Boot API, MySQL, and Nginx on one ARM64 staging EC2 instance. Nginx only proxies `/api/`; it deliberately returns `404` for `/` and `/actuator/`. No frontend asset, Vite server, Valkey, S3, RDS, ECS, ALB, TLS certificate, or domain is configured by this change.

The later frontend delivery must add static frontend assets to Nginx and retain the `/api/` proxy route. That work needs its own issue, review, and deploy verification before this can be called a same-origin user release.

This workflow is deliberately limited to the `staging` GitHub Environment and deploys only successful `dev` commits. Production is not configured here. A future production route must use a separate production account or clearly isolated resources, EC2 instance, IAM role, GitHub Environment with required reviewers, and a `main`-only workflow.

## Flow

1. `Backend CI` succeeds on a push to `dev`.
2. `Backend CD (Staging)` checks out that exact successful commit and builds `linux/arm64` from `backend/Dockerfile`.
3. The image is pushed to private ECR with only the full 40-character Git SHA tag.
4. GitHub Actions sends the compose file, Nginx configuration, and deploy script through SSM to `/opt/miriyum`.
5. The EC2 script logs in to ECR, pulls the immutable image, runs Docker Compose, and checks `http://127.0.0.1:8080/actuator/health`.

The runtime `.env` is created manually on EC2 and remains server-local. The CD workflow never creates it, uploads it, or writes its values to GitHub Actions logs.

## One-time AWS setup

1. Configure a GitHub OIDC provider with issuer `https://token.actions.githubusercontent.com` and audience `sts.amazonaws.com`.
2. Create `miriyum-github-staging-cd-role` and restrict its trust policy to `repo:sparta-spring4/Commerce-Final-Project-MiriYum:ref:refs/heads/dev`.
3. Grant the role only ECR push access to `miriyum-backend` and SSM command/invocation access to the staging EC2 instance using `AWS-RunShellScript`. Do not reuse this role for a future production instance.
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
2. Replace every `replace-with-...` value with a unique staging value.
3. Run `chmod 600 /opt/miriyum/.env`.
4. Confirm the instance role has `AmazonEC2ContainerRegistryReadOnly` and Systems Manager access.
5. Confirm the security group allows TCP `80` only as required for the API. Do not expose MySQL `3306` or backend `8080`.

## Release and rollback

An ordinary push to `dev` deploys only to staging after `Backend CI` succeeds. A manual `Backend CD (Staging)` dispatch is allowed only from `dev` and accepts a full 40-character SHA tag. Use it to deploy an already-pushed previous ECR image when rolling back staging.

After each deployment, record the GitHub Actions run URL, ECR image digest, SSM command ID, and EC2 loopback health result. Until those four runtime results exist, deployment evidence remains `NOT RUN`.
