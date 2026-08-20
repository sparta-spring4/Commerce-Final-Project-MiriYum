# Staging runtime troubleshooting

## Safety boundary

Use this runbook only for the staging EC2 host and its Docker Compose stack.
Print `SET`/`EMPTY`, HTTP status, container image tag, and health status only.
Do not print `.env`, secret values, cookie values, authorization headers,
Kakao/OpenAI responses, database passwords, or consumer identifiers.

## Local Vite request returns 301, 503, or a connection error

The frontend API client uses relative `/api/...` paths. A browser request to
`http://localhost:5173/api/...` therefore reaches Vite first; Vite must proxy it
to staging. This is distinct from a browser navigating directly to the staging
API hostname, which is an API endpoint rather than a frontend page.

1. Verify `.env.local` has a trailing-slash-free
   `MIRIYUM_VITE_PROXY_TARGET=https://staging-api.miriyum.click`.
2. Verify `vite.config.ts` reads that value with Vite `loadEnv(mode, ...)` and
   uses `http://127.0.0.1:8080` only as a local fallback.
3. Stop and restart the Vite dev server after changing `.env.local`.
4. In DevTools, inspect the original `localhost:5173/api/...` request and the
   final response. A direct `GET` to a POST-only authorization endpoint is
   expected to be `405`, not evidence that the proxy is broken.

## Staging backend is healthy but a conditional setting appears absent

Compose renders environment values at container creation time. A host `.env`
change does not modify the already running backend container.

```sh
cd /opt/miriyum
sudo docker compose --env-file .env -f docker-compose.prod.yml config --format json \
  | jq -r '.services.backend.environment.MIRIYUM_STORE_GEOCODING_REST_API_KEY // ""' \
  | { read value; [ -n "$value" ] && echo RENDERED_ENV=SET || echo RENDERED_ENV=EMPTY; }

backend_id=$(sudo docker compose --env-file .env -f docker-compose.prod.yml ps -q backend)
sudo docker inspect --format '{{.Config.Image}}' "$backend_id"
```

If the rendered value is set but the running container is not on the approved
image, redeploy the approved immutable image SHA through staging CD. Then verify
loopback health; do not infer a successful deployment from the workflow start.

```sh
curl -fsS http://127.0.0.1:8080/actuator/health
```

For store geocoding, the canonical runtime name is
`MIRIYUM_STORE_GEOCODING_REST_API_KEY`. The Kakao OAuth REST key is a separate
setting and must not be substituted silently. Verify only whether the canonical
key is non-empty, then use a controlled synthetic store request to observe an
HTTP status without writing a provider response or key to logs.

## Kakao login returns signup for a previously linked account

First distinguish the account-link state from a browser Kakao session:

1. Confirm the backend's non-secret active fingerprint configuration is present
   (`ACTIVE_KEY_VERSION` and active secret are `SET`).
2. Confirm the consumer's Kakao link exists using the approved internal
   diagnostic, without printing the Kakao identifier or account ID.
3. Log out of the Kakao account in the browser, select the intended Kakao
   account, and retry the authorization flow.
4. If the same account still enters signup, capture only request method, status,
   application error code, deployed image SHA, and active key version for the
   Auth owner. Do not rotate a fingerprint secret as a browser-session fix.

## LLM search is enabled but metric data is missing

Verify the running backend first:

```sh
sudo docker compose --env-file .env -f docker-compose.prod.yml exec -T backend sh -c '
  for key in OPENAI_API_KEY MIRIYUM_STORE_SEARCH_LLM_ENABLED MIRIYUM_STORE_SEARCH_LLM_MODEL MIRIYUM_CLOUDWATCH_METRICS_ENABLED MIRIYUM_CLOUDWATCH_METRICS_NAMESPACE; do
    value=$(printenv "$key" 2>/dev/null || true)
    [ -n "$value" ] && echo "$key=SET" || echo "$key=EMPTY"
  done'
```

With the feature enabled, use a non-sensitive search and verify its HTTP result.
Then inspect the `MiriYum/Staging` namespace in the CloudWatch console. Expected
meter families are `miriyum.search.llm.calls`, `latency`, `outcomes`, and
`tokens`, with Micrometer statistic suffixes such as `.count`, `.sum`, `.avg`,
or `.max`.

- `purpose=store_search` and `purpose=menu_alternative` are separate evidence.
- `sensitive_input` is an expected protected outcome for disallowed input.
- `timeout` or provider failure can still return a successful search response
  because deterministic search/alternative fallback remains available.
- Lack of `cloudwatch:ListMetrics` on the EC2 instance role does not prevent the
  application from publishing metrics. Inspect with an approved read-only IAM
  principal or the CloudWatch console instead of widening the runtime role.
