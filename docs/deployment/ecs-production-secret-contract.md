# ECS production Secret contract

Issue: [#318](https://github.com/sparta-spring4/Commerce-Final-Project-MiriYum/issues/318)

## Purpose

The production ECS task must not contain application secret values. The task definition stores only a Secrets Manager ARN and the JSON key to inject. The value is registered once in AWS Secrets Manager after the production deployment configuration is approved.

`deploy/ecs/production-secret-contract.json` is the reviewed list of required keys. `deploy/ecs/production-task-definition.json` is a value-free template. Backend CI verifies that the template references every required key before the template can be used for deployment.

Backend CI also extracts `MIRIYUM_*` references without a default value from `backend/src/main/resources/application.yml`. Every such setting must be listed in `requiredSecrets`; adding a new mandatory application secret without updating the reviewed contract fails CI. Settings with a default value and profile-specific configuration remain a separate follow-up decision.

## Secret structure

Create one JSON secret named `miriyum/production/application` after team approval. Its JSON keys must match the task definition exactly.

```json
{
  "MIRIYUM_DB_URL": "jdbc:mysql://...",
  "MIRIYUM_DB_USERNAME": "...",
  "MIRIYUM_DB_PASSWORD": "...",
  "MIRIYUM_JWT_SECRET": "...",
  "MIRIYUM_STORE_GEOCODING_REST_API_KEY": "...",
  "MIRIYUM_NOTIFICATION_HISTORY_CURSOR_SECRET": "...",
  "MIRIYUM_VALKEY_PASSWORD": "..."
}
```

Do not commit values, the final secret ARN, database endpoints, ALB domain names, or task role ARNs.

## Store geocoding key migration

`MIRIYUM_STORE_GEOCODING_REST_API_KEY` is the canonical production JSON key for store address verification. It is independent from the conditional Kakao OAuth keys. The application compatibility configuration prefers the canonical key and reads `MIRIYUM_KAKAO_LOCAL_REST_API_KEY` only as a temporary fallback.

Use this order when changing the real production secret:

1. Add the canonical JSON key to the approved Secrets Manager secret while retaining the legacy JSON key.
2. Register and deploy the task definition that selects the canonical JSON key. Do not place either value in the task definition.
3. Verify one synthetic store-registration smoke and confirm that task logs and deployment artifacts contain neither key value nor provider response body.
4. Remove the legacy JSON key only after staging Compose and production ECS both have canonical-name runtime evidence. Remove the application fallback in a later reviewed change.

The value-free repository template and verifier prove the reference contract only. They are not evidence that the real Secrets Manager value or production runtime is configured.

## Conditional secrets

The initial production task keeps Kakao OAuth and payment disabled. Their secret references are required only when the corresponding feature flag becomes `true`.

| Feature flag | Required keys when enabled |
| --- | --- |
| `MIRIYUM_KAKAO_ENABLED` | `MIRIYUM_KAKAO_REST_API_KEY`, `MIRIYUM_KAKAO_CLIENT_SECRET`, `MIRIYUM_KAKAO_STATE_SECRET`, `MIRIYUM_KAKAO_SIGN_UP_TICKET_SECRET`, `MIRIYUM_KAKAO_IDENTITY_FINGERPRINT_ACTIVE_SECRET` |
| `MIRIYUM_PAYMENT_ENABLED` | `MIRIYUM_PAYMENT_CURSOR_SECRET`, `MIRIYUM_PORTONE_API_SECRET`, `MIRIYUM_PORTONE_WEBHOOK_SECRET` |

When a feature is enabled, add its actual values to the same JSON secret and replace its placeholder references in the task definition in the same PR.

## Deployment prerequisites

Before registering the task definition, replace these placeholders through the approved production setup:

- ECS execution role ARN and task role ARN
- ECR image URI
- HTTPS origin, Valkey endpoint, and CloudWatch log group
- application Secret ARN

`MIRIYUM_VALKEY_SSL_ENABLED=true` is a reviewed non-secret task environment value, not a JSON secret key. It is required because the production ElastiCache Valkey connection uses TLS; the local and staging default remains `false` for the Docker Compose Valkey container.

The execution role needs `secretsmanager:GetSecretValue` only for the application secret ARN. The task role receives only the runtime permissions the application needs; it must not receive broad Secrets Manager access.

## Local verification

```bash
python3 scripts/test-verify-production-task-definition.py
python3 scripts/verify-production-task-definition.py \
  deploy/ecs/production-secret-contract.json \
  deploy/ecs/production-task-definition.json \
  --application-config backend/src/main/resources/application.yml
```

Expected result: both commands exit with code `0` and print no secret value.
