# Staging Load-Test Operator Runbook

## Purpose

This runbook lets an approved load-test operator temporarily permit one public IPv4 address in the staging authentication rate limit, run the k6 scenario, and restore the default limit. It uses only the `Staging Load-Test Control` GitHub Actions workflow.

The operator does not need AWS Console, EC2, SSM Session Manager, Secrets Manager, or shell access.

## Access Boundary

- GitHub repository write access is required to dispatch the workflow. GitHub Actions cannot grant dispatch access for only one workflow.
- The workflow can perform only seven fixed actions: `enable-load-test`, `disable-load-test`, `enable-sse`, `disable-sse`, `safe-recovery`, `interrupt-valkey`, and `recover-valkey`.
- It accepts an already-built immutable 40-character Git SHA image tag. It never builds a new image or deploys an arbitrary tag.
- `safe-recovery` can only set the predefined staging flags to `false`: runtime JSON, payment, S3 storage, and store-search LLM. Re-enabling a feature still requires the normal deployment-owner process.
- `interrupt-valkey` has no duration or command input. It requires an Issue number and one-time UUID, waits for the dispatch actor's exact FIRE comment after OIDC preparation, and schedules only the reviewed staging Valkey interruption. It stops Valkey for 10 seconds, starts it in the same bounded SSM command, and waits for Docker health.
- `recover-valkey` only starts the staging Valkey container and waits for Docker health.

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

## Inject and Recover a Fixed Valkey Interruption

Use this procedure only for an approved SSE `recovery` profile. The selected `image_tag` must equal the latest successful `staging-backend` deployment SHA; the workflow rejects an older or different SHA.

1. Prepare a fresh synthetic `WAITING` team in the approved store. A successful earlier recovery ends its team as terminal `CANCELLED`, so never reuse that team for another attempt. The fixture fingerprint proves only the static fixture file and does not prove current database state.
2. Confirm the approved synthetic store has no other Waiting writer during the recovery window.
3. Generate one new UUID for this attempt. It is a coordination identifier, not a credential, but never reuse it.
4. Before starting k6, open `Staging Load-Test Control` on branch `dev`, choose `interrupt-valkey`, enter the approved SHA, the owning Issue number, and the new rendezvous UUID, and leave `source_ip` empty. Record the resulting GitHub Actions run ID.
5. Wait until the workflow has completed deployment-SHA validation, checkout and OIDC and is running `Wait for the exact recovery fire marker`. Do not post the marker before this step is active.
6. Start the clean staging recovery harness with `SSE_RECOVERY_RENDEZVOUS_APPROVED=true`, repository `sparta-spring4/Commerce-Final-Project-MiriYum`, the same Issue number, Actions run ID and rendezvous UUID. Do not set `SSE_RECOVERY_ARM_DELAY_SECONDS` in staging rendezvous mode.
7. The harness setup verifies the owner WAITING list before arming. If it exits without `SSE_RECOVERY_READY`, cancel the waiting workflow; no FIRE marker or Valkey interruption is allowed.
8. After `SSE_RECOVERY_READY`, post exactly one Issue comment as the same actor who dispatched the workflow: `SSE_RECOVERY_FIRE run_id=<actions-run-id> rendezvous_id=<uuid>`. Do not include credentials, fixture values or resource IDs.
9. The prepared workflow schedules the fixed interruption. After the remote script passes runtime image, Compose, health and remaining-lead preflight, it atomically publishes the exact epoch to a root-only readiness file. A separate fixed SSM probe must validate that file while the interruption command is still `InProgress` before the workflow posts `SSE_RECOVERY_ARMED run_id=... rendezvous_id=... execute_at_epoch=...` as `github-actions[bot]`. k6 accepts only that exact bounded marker and performs the approved mutation at the reviewed offset inside the ten-second interruption.
10. Wait for both the workflow and harness. Confirm MySQL correction, the changed signal, owner HTTP verification, cleanup and all thresholds. If either side fails, the attempt is not recovery evidence; use only safe aggregate counters and do not copy comment responses, response bodies or identifiers into the Issue.

For recovery diagnosis, correlate only the allowlisted `staging_valkey_control_phase` and
`sse_recovery_phase` UTC epochs. The former records preflight readiness, stop, start and
healthy boundaries; the latter records initial event, ARMED observation, mutation, recovery
event and stream completion. These markers contain no account, store, team, cursor, image or
credential values. Raw SSM stdout and stderr remain excluded from workflow output.

The workflow times out without SSM submission when a valid FIRE marker is not received. If the fixed readiness probe cannot run concurrently, cannot validate the exact epoch, or the main command leaves `InProgress`, the workflow does not post ARMED and k6 does not consume the Waiting fixture. Once SSM starts, the remote script attempts to start Valkey from its exit and signal traps. If the action fails, is cancelled, or times out, immediately run `recover-valkey` with the same latest deployed SHA and do not continue testing until that action succeeds and staging private health is `UP`. Do not manually run both control actions concurrently; the workflow serializes them with other staging load-test controls and staging deployment commands.

Before Compose validation or any Valkey operation, the remote script resolves exactly one running, non-one-off backend container and one running, non-one-off frontend container from the fixed `miriyum` Compose project labels. It passes their image references only to the Compose process and never prints them. If either service is stopped, only a Compose one-off container exists, or either binding is missing or ambiguous, the action fails closed with `phase=preflight reason=runtime-image-binding-failed` before it starts or stops Valkey.

On failure, the Actions log reports the SSM status, response code, and only bounded diagnostics in the form `event=staging_valkey_control_failed action=... phase=... reason=... exit_code=...`. It does not print the full remote stdout or stderr. If the remote command ends before it can emit a diagnostic event, the workflow reports `phase=remote-command reason=remote-command-failed` with the command ID. Use the phase and fixed reason for triage; do not copy environment values, Compose output, credentials, tokens, or Valkey data into the Issue.

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
- a custom Valkey interruption duration, arbitrary container, or arbitrary shell command;
- production deployment or production rate-limit changes.

Escalate those cases to the deployment owner with the Actions run URL, selected SHA, health result, and relevant CloudWatch or workflow error evidence.
