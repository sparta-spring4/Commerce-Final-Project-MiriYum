# Staging Load-Test Operator Runbook

## Purpose

This runbook lets an approved load-test operator temporarily permit one public IPv4 address in the staging authentication rate limit, run the k6 scenario, and restore the default limit. It uses only the `Staging Load-Test Control` GitHub Actions workflow.

The operator does not need AWS Console, EC2, SSM Session Manager, Secrets Manager, or shell access.

## Access Boundary

- GitHub repository write access is required to dispatch the workflow. GitHub Actions cannot grant dispatch access for only one workflow.
- The workflow can perform only three fixed actions: `enable-load-test`, `disable-load-test`, and `safe-recovery`.
- It accepts an already-built immutable 40-character Git SHA image tag. It never builds a new image or deploys an arbitrary tag.
- `safe-recovery` can only set the predefined staging flags to `false`: runtime JSON, payment, S3 storage, and store-search LLM. Re-enabling a feature still requires the normal deployment-owner process.

## Before Starting

1. Confirm the selected SHA passed CI and that its backend and frontend ECR images already exist.
2. Record the selected SHA, start time, operator, and the CloudWatch observer in the load-test Issue.
3. Find the public IPv4 of the network that will run k6. Do not use a private address such as `10.x.x.x` or `192.168.x.x`.
4. Open GitHub Actions, select `Staging Load-Test Control`, and choose `Run workflow` on branch `dev`.

## Enable the Temporary Exception

1. Set `action` to `enable-load-test`.
2. Enter the selected 40-character SHA in `image_tag`.
3. Enter the operator's public IPv4 in `source_ip`.
4. Run the workflow and wait for the delegated `Backend CD (Staging)` job to succeed.
5. Confirm the staging private health endpoint is `UP` before starting k6.

The workflow does not print the IP in shell output. The GitHub workflow input is still visible to people who can view that Actions run, so treat it as operational metadata rather than a secret.

## Run and Observe k6

1. Run only the approved k6 scenario from the load-test harness.
2. The assigned CloudWatch observer records the start and finish time, selected SHA, request result, and dashboard capture.
3. Stop the test immediately if staging health becomes non-`UP`, error rates rise unexpectedly, or an alarm fires.

## Restore the Default Rate Limit

1. In `Staging Load-Test Control`, choose `Run workflow` again on branch `dev`.
2. Set `action` to `disable-load-test`.
3. Enter the same SHA in `image_tag`.
4. Leave `source_ip` empty.
5. Wait for the delegated staging CD to succeed and verify private health is `UP`.
6. Run `performance/k6/recovery-rate-limit.js` from the harness. It must show five accepted login attempts followed by the default sixth-request `429` response.
7. Record the recovery evidence in the load-test Issue.

## Safe Recovery

Use `safe-recovery` only when staging is unhealthy because one of the predefined optional runtime features was enabled incorrectly.

1. Set `action` to `safe-recovery`.
2. Enter the known-good 40-character SHA in `image_tag`.
3. Leave `source_ip` empty.
4. Wait for deployment success and confirm health is `UP`.
5. Notify the deployment owner. The affected feature remains disabled until it is re-enabled through the normal reviewed deployment process.

## Escalate Instead of Using This Workflow

Do not attempt to repair these incidents through the load-test control workflow:

- missing or invalid secrets, IAM permissions, or certificates;
- a missing ECR image, wrong SHA, migration failure, or database outage;
- arbitrary environment-variable changes;
- production deployment or production rate-limit changes.

Escalate those cases to the deployment owner with the Actions run URL, selected SHA, health result, and relevant CloudWatch or workflow error evidence.
