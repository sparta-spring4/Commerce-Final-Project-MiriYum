#!/usr/bin/env bash
# SSM이 EC2에서 실행한다. 변경 불가능한 ECR 이미지를 받고 127.0.0.1의 health를 확인한다.
set -Eeuo pipefail

APP_DIR="${APP_DIR:-/opt/miriyum}"
COMPOSE_FILE="${COMPOSE_FILE:-${APP_DIR}/docker-compose.prod.yml}"
ENV_FILE="${ENV_FILE:-${APP_DIR}/.env}"
HEALTH_URL="${HEALTH_URL:-http://127.0.0.1:8080/actuator/health}"
HEALTH_TIMEOUT_SECONDS="${HEALTH_TIMEOUT_SECONDS:-90}"
MYSQL_HEALTH_TIMEOUT_SECONDS="${MYSQL_HEALTH_TIMEOUT_SECONDS:-210}"
VALKEY_HEALTH_TIMEOUT_SECONDS="${VALKEY_HEALTH_TIMEOUT_SECONDS:-190}"
RISK_EVENT_BACKFILL_MAX_SCAN_PAGES="${RISK_EVENT_BACKFILL_MAX_SCAN_PAGES:-10000}"
CLOUDWATCH_NAMESPACE="${MIRIYUM_CLOUDWATCH_NAMESPACE:-MiriYum/Staging}"
OPENAI_API_KEY_PARAMETER_NAME="${OPENAI_API_KEY_PARAMETER_NAME:-/miriyum/shared/openai-api-key}"

compose_command() (
  # Docker Compose gives the invoking shell precedence over --env-file values.
  # Keep these runtime values sourced exclusively from the server-side .env file.
  unset OPENAI_API_KEY MIRIYUM_STORE_SEARCH_LLM_ENABLED
  docker compose "$@"
)

publish_deployment_health() {
  local value="$1"
  aws cloudwatch put-metric-data \
    --namespace "${CLOUDWATCH_NAMESPACE}" \
    --metric-data "MetricName=DeploymentHealth,Value=${value},Unit=Count" \
    >/dev/null || echo "Warning: CloudWatch deployment health metric was not published." >&2
}

validate_runtime_environment() {
  local compose=(compose_command --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}")

  # Compose owns required-variable declarations, so this stays in sync with new runtime keys.
  if ! "${compose[@]}" config --quiet; then
    echo "Runtime environment validation failed. Check required keys in ${ENV_FILE}; values are not printed." >&2
    return 1
  fi
}

sync_llm_runtime_environment() {
  local api_key temporary_env llm_enabled

  llm_enabled=$(awk -F= '$1 == "MIRIYUM_STORE_SEARCH_LLM_ENABLED" { print substr($0, index($0, "=") + 1); exit }' "${ENV_FILE}")
  llm_enabled="${llm_enabled:-false}"
  case "${llm_enabled}" in
    false)
    temporary_env=$(mktemp "${ENV_FILE}.XXXXXX")
    chmod 600 "${temporary_env}"
    awk '!/^OPENAI_API_KEY=/' "${ENV_FILE}" > "${temporary_env}"
    mv "${temporary_env}" "${ENV_FILE}"
    echo "LLM runtime disabled; skipping OpenAI parameter synchronization."
    return 0
    ;;
    true) ;;
    *)
      echo "Invalid MIRIYUM_STORE_SEARCH_LLM_ENABLED value: ${llm_enabled}" >&2
      return 1
      ;;
  esac

  api_key=$(aws ssm get-parameter \
    --region "${AWS_REGION}" \
    --name "${OPENAI_API_KEY_PARAMETER_NAME}" \
    --with-decryption \
    --query 'Parameter.Value' \
    --output text)

  if [[ -z "${api_key}" || "${api_key}" == "None" ]]; then
    echo "OpenAI API key parameter is empty: ${OPENAI_API_KEY_PARAMETER_NAME}" >&2
    return 1
  fi

  temporary_env=$(mktemp "${ENV_FILE}.XXXXXX")
  chmod 600 "${temporary_env}"
  awk '!/^OPENAI_API_KEY=/' \
    "${ENV_FILE}" > "${temporary_env}"
  printf 'OPENAI_API_KEY=%s\n' "${api_key}" >> "${temporary_env}"
  if ! grep -q '^MIRIYUM_STORE_SEARCH_LLM_ENABLED=' "${temporary_env}"; then
    printf '%s\n' 'MIRIYUM_STORE_SEARCH_LLM_ENABLED=true' >> "${temporary_env}"
  fi
  if ! grep -q '^MIRIYUM_STORE_SEARCH_LLM_MODEL=' "${temporary_env}"; then
    printf '%s\n' 'MIRIYUM_STORE_SEARCH_LLM_MODEL=gpt-4o-mini' >> "${temporary_env}"
  fi
  mv "${temporary_env}" "${ENV_FILE}"
  unset api_key
}

wait_for_mysql_health() {
  local compose=(compose_command --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}")
  local deadline container_id health

  deadline=$((SECONDS + MYSQL_HEALTH_TIMEOUT_SECONDS))
  while :; do
    container_id="$("${compose[@]}" ps -q mysql)"
    if [[ -n "${container_id}" ]]; then
      health="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}missing{{end}}' "${container_id}" 2>/dev/null || echo missing)"
      if [[ "${health}" == "healthy" ]]; then
        return 0
      fi
    else
      health="not-running"
    fi

    if (( SECONDS >= deadline )); then
      echo "MySQL health check timed out after ${MYSQL_HEALTH_TIMEOUT_SECONDS}s (last status: ${health})." >&2
      return 1
    fi
    sleep 2
  done
}

wait_for_valkey_health() {
  local compose=(compose_command --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}")
  local deadline container_id health

  deadline=$((SECONDS + VALKEY_HEALTH_TIMEOUT_SECONDS))
  while :; do
    container_id="$("${compose[@]}" ps -q valkey)"
    if [[ -n "${container_id}" ]]; then
      health="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}missing{{end}}' "${container_id}" 2>/dev/null || echo missing)"
      if [[ "${health}" == "healthy" ]]; then
        return 0
      fi
    else
      health="not-running"
    fi

    if (( SECONDS >= deadline )); then
      echo "Valkey health check timed out after ${VALKEY_HEALTH_TIMEOUT_SECONDS}s (last status: ${health})." >&2
      return 1
    fi
    sleep 2
  done
}

verify_valkey() {
  local compose=(compose_command --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}")
  local unauthenticated_result host_port

  if ! wait_for_valkey_health; then
    return 1
  fi

  unauthenticated_result="$("${compose[@]}" exec -T valkey valkey-cli ping 2>&1 || true)"
  if ! grep -qx 'NOAUTH Authentication required\.' <<<"${unauthenticated_result}"; then
    echo "Unauthenticated Valkey ping did not return NOAUTH." >&2
    return 1
  fi

  if ! "${compose[@]}" exec -T valkey sh -ec 'REDISCLI_AUTH="$MIRIYUM_VALKEY_PASSWORD" valkey-cli ping' \
    | grep -qx PONG; then
    echo "Authenticated Valkey ping did not return PONG." >&2
    return 1
  fi

  host_port="$("${compose[@]}" port valkey 6379 2>/dev/null || true)"
  if [[ -n "${host_port}" ]]; then
    echo "Valkey host port must not be published: ${host_port}" >&2
    return 1
  fi
}

recover_nginx_http() {
  local compose=(compose_command --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}")

  # A broken TLS configuration must not leave Nginx unavailable after a backend deployment.
  MIRIYUM_STAGING_FORCE_HTTP=true "${compose[@]}" up -d --force-recreate nginx || true
}

verify_nginx() {
  local compose=(compose_command --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}")

  if ! "${compose[@]}" ps --status running --services nginx | grep -qx nginx; then
    echo "Nginx is not running after deployment." >&2
    return 1
  fi

  if ! "${compose[@]}" exec -T nginx nginx -t; then
    echo "Nginx configuration validation failed after deployment." >&2
    return 1
  fi

  # TLS must either have both readable files or the selector must render HTTP-only.
  if ! "${compose[@]}" exec -T nginx sh -ec '
    if grep -Fqx "    listen 443 ssl;" /etc/nginx/conf.d/default.conf; then
      test -r "/etc/letsencrypt/live/$STAGING_DOMAIN/fullchain.pem"
      test -r "/etc/letsencrypt/live/$STAGING_DOMAIN/privkey.pem"
    fi
  '; then
    echo "Nginx selected TLS without readable certificate and private key files." >&2
    return 1
  fi
}

verify_frontend() {
  local compose=(compose_command --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}")
  local frontend_domain

  if ! "${compose[@]}" ps --status running --services frontend | grep -qx frontend; then
    echo "Frontend is not running after deployment." >&2
    return 1
  fi

  if ! "${compose[@]}" exec -T frontend wget -q -O /dev/null http://127.0.0.1/; then
    echo "Frontend container root did not return HTTP 200." >&2
    return 1
  fi

  if ! "${compose[@]}" exec -T frontend wget -q -O /dev/null http://127.0.0.1/sign-in; then
    echo "Frontend SPA fallback did not return HTTP 200." >&2
    return 1
  fi

  frontend_domain="$("${compose[@]}" exec -T nginx sh -ec 'printf %s "$STAGING_FRONTEND_DOMAIN"')"
  if [[ -z "${frontend_domain}" ]]; then
    echo "Staging frontend domain is empty." >&2
    return 1
  fi

  if "${compose[@]}" exec -T nginx grep -Fqx "    listen 443 ssl;" /etc/nginx/conf.d/default.conf; then
    if ! curl --fail --silent --show-error \
      --resolve "${frontend_domain}:443:127.0.0.1" \
      "https://${frontend_domain}/" >/dev/null; then
      echo "Gateway frontend root did not return HTTP 200 over HTTPS." >&2
      return 1
    fi
    if ! curl --fail --silent --show-error \
      --resolve "${frontend_domain}:443:127.0.0.1" \
      "https://${frontend_domain}/sign-in" >/dev/null; then
      echo "Gateway frontend SPA fallback did not return HTTP 200 over HTTPS." >&2
      return 1
    fi
    return 0
  fi

  if ! curl --fail --silent --show-error -H "Host: ${frontend_domain}" http://127.0.0.1/ >/dev/null; then
    echo "Gateway frontend root did not return HTTP 200." >&2
    return 1
  fi
  if ! curl --fail --silent --show-error -H "Host: ${frontend_domain}" http://127.0.0.1/sign-in >/dev/null; then
    echo "Gateway frontend SPA fallback did not return HTTP 200." >&2
    return 1
  fi
}

# 구버전 롤백 중 생성된 marker까지 다음 전달 대상에서 누락되지 않게 매 배포 이관한다.
backfill_pending_risk_event_index() {
  local compose=(compose_command --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}")
  local marker_pattern="auth:risk:pending:*"
  local pending_index="auth:risk:pending-index"

  case "${RISK_EVENT_BACKFILL_MAX_SCAN_PAGES}" in
    ''|0|*[!0-9]*)
      echo "RISK_EVENT_BACKFILL_MAX_SCAN_PAGES must be a positive integer." >&2
      return 1
      ;;
  esac

  "${compose[@]}" exec -T valkey sh -ec '
    marker_pattern="$1"
    pending_index="$2"
    max_scan_pages="$3"
    cursor=0
    scan_pages=0

    while :; do
      scan_result="$(REDISCLI_AUTH="$MIRIYUM_VALKEY_PASSWORD" valkey-cli --raw SCAN "$cursor" MATCH "$marker_pattern" COUNT 100)"
      cursor="$(printf "%s\\n" "$scan_result" | sed -n "1p")"
      case "$cursor" in
        ""|*[!0-9]*)
          echo "Invalid SCAN cursor: $cursor" >&2
          exit 1
          ;;
      esac
      scan_pages=$((scan_pages + 1))
      if [ "$scan_pages" -gt "$max_scan_pages" ]; then
        echo "Risk event index backfill exceeded $max_scan_pages SCAN pages." >&2
        exit 1
      fi
      printf "%s\\n" "$scan_result" | tail -n +2 | while IFS= read -r marker_key; do
        [ -n "$marker_key" ] || continue
        REDISCLI_AUTH="$MIRIYUM_VALKEY_PASSWORD" valkey-cli SADD "$pending_index" "$marker_key" >/dev/null
      done

      [ "$cursor" = "0" ] && break
    done
  ' sh "${marker_pattern}" "${pending_index}" "${RISK_EVENT_BACKFILL_MAX_SCAN_PAGES}"
}

# 배포 전 DB에 기록된 재사용 횟수를 Valkey counter에 이관해 marker 재생성 시 횟수가 줄지 않게 한다.
backfill_risk_event_occurrence_counters() {
  local compose=(compose_command --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}")
  local rows event_key occurrence_count namespace family_id token_hash counter_key family_key

  if ! rows="$("${compose[@]}" exec -T mysql sh -ec '
    MYSQL_PWD="$MYSQL_PASSWORD" mysql --batch --skip-column-names --raw \
      -u "$MYSQL_USER" "$MYSQL_DATABASE" \
      -e "SELECT event_key, occurrence_count FROM auth_risk_events"
  ')"; then
    echo "Risk event occurrence counter backfill could not read MySQL." >&2
    return 1
  fi

  while IFS=$'\t' read -r event_key occurrence_count; do
    [[ -n "${event_key}" ]] || continue
    if [[ "${event_key}" =~ ^auth:risk:pending:([A-Za-z0-9_-]+):([A-Za-z0-9_-]+):([0-9a-f]{64})$ ]]; then
      namespace="${BASH_REMATCH[1]}"
      family_id="${BASH_REMATCH[2]}"
      token_hash="${BASH_REMATCH[3]}"
    else
      echo "Risk event occurrence counter backfill found an invalid row." >&2
      return 1
    fi
    if [[ ! "${occurrence_count}" =~ ^[1-9][0-9]*$ ]]; then
      echo "Risk event occurrence counter backfill found an invalid row." >&2
      return 1
    fi
    counter_key="auth:risk:occurrence:${namespace}:${family_id}:${token_hash}"
    family_key="auth:refresh:${namespace}:${family_id}"

    if ! "${compose[@]}" exec -T valkey sh -ec '
      counter_key="$1"
      family_key="$2"
      marker_key="$3"
      occurrence_count="$4"
      REDISCLI_AUTH="$MIRIYUM_VALKEY_PASSWORD" valkey-cli --raw EVAL "
        local function isPositiveInteger(value)
          return value ~= false and string.match(value, \"^[1-9][0-9]*$\") ~= nil
        end

        local function isGreater(left, right)
          if string.len(left) ~= string.len(right) then
            return string.len(left) > string.len(right)
          end
          return left > right
        end

        local familyExpiresAt = redis.call(\"EXPIRETIME\", KEYS[2])
        if familyExpiresAt <= 0 then
          redis.call(\"DEL\", KEYS[1])
          return 0
        end
        local currentOccurrenceCount = redis.call(\"GET\", KEYS[1])
        local markerOccurrenceCount = redis.call(\"HGET\", KEYS[3], \"occurrenceCount\")
        local greatestOccurrenceCount = ARGV[1]
        if isPositiveInteger(currentOccurrenceCount)
            and isGreater(currentOccurrenceCount, greatestOccurrenceCount) then
          greatestOccurrenceCount = currentOccurrenceCount
        end
        if isPositiveInteger(markerOccurrenceCount)
            and isGreater(markerOccurrenceCount, greatestOccurrenceCount) then
          greatestOccurrenceCount = markerOccurrenceCount
        end
        redis.call(\"SET\", KEYS[1], greatestOccurrenceCount)
        redis.call(\"EXPIREAT\", KEYS[1], familyExpiresAt)
        return 1
      " 3 "$counter_key" "$family_key" "$marker_key" "$occurrence_count" >/dev/null
    ' sh "${counter_key}" "${family_key}" "${event_key}" "${occurrence_count}"; then
      echo "Risk event occurrence counter backfill could not update Valkey." >&2
      return 1
    fi
  done <<<"${rows}"
}

# 인스턴스 역할이 배포 시 ECR 토큰을 받아오므로 레지스트리 비밀번호를 저장하지 않는다.
main() {
  local account_id registry deadline

  : "${AWS_REGION:?AWS_REGION must be set}"
  : "${BACKEND_IMAGE:?BACKEND_IMAGE must be set to an immutable ECR image tag}"
  # 같은 SHA의 frontend 태그가 기본값이다. CD는 이 값을 명시적으로 전달한다.
  FRONTEND_IMAGE="${FRONTEND_IMAGE:-${BACKEND_IMAGE}-frontend}"

  if [[ ! -f "${ENV_FILE}" ]]; then
    echo "Missing runtime environment file: ${ENV_FILE}" >&2
    return 1
  fi

  sync_llm_runtime_environment
  validate_runtime_environment

  for command in aws curl docker; do
    command -v "${command}" >/dev/null
  done

  account_id=$(aws sts get-caller-identity --query Account --output text)
  registry="${account_id}.dkr.ecr.${AWS_REGION}.amazonaws.com"

  aws ecr get-login-password --region "${AWS_REGION}" \
    | docker login --username AWS --password-stdin "${registry}"

  export BACKEND_IMAGE FRONTEND_IMAGE

# 실행 환경은 서버에만 두고 이미지와 배포 파일만 갱신한다.
  compose_command --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" pull
  # 구버전 writer를 멈춘 뒤에만 위험 사건 상태를 snapshot/backfill해 전환 중 count가 작아지지 않게 한다.
  if ! compose_command --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" stop backend; then
    echo "Could not stop the existing backend before risk event state backfill." >&2
    publish_deployment_health 0
    return 1
  fi
  # 새 backend가 pending Set만 읽기 시작하기 전에 Valkey와 기존 marker 인덱스를 준비한다.
  compose_command --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" up -d mysql valkey

  if ! wait_for_mysql_health; then
    publish_deployment_health 0
    compose_command --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" ps || true
    compose_command --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" logs --tail 100 mysql || true
    return 1
  fi

  if ! verify_valkey; then
    publish_deployment_health 0
    compose_command --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" ps || true
    compose_command --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" logs --tail 100 valkey || true
    return 1
  fi

  if ! backfill_pending_risk_event_index || ! backfill_risk_event_occurrence_counters; then
    # Set-only 전달 worker는 marker index와 재사용 횟수 이관이 완료된 상태에서만 시작한다.
    echo "Risk event state backfill failed; aborting deployment before Set-only delivery starts." >&2
    publish_deployment_health 0
    compose_command --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" ps || true
    compose_command --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" logs --tail 100 valkey || true
    return 1
  fi

  compose_command --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" up -d --remove-orphans

  if ! verify_nginx; then
    recover_nginx_http
    publish_deployment_health 0
    compose_command --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" ps || true
    compose_command --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" logs --tail 100 nginx || true
    return 1
  fi

  if ! verify_frontend; then
    publish_deployment_health 0
    compose_command --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" ps || true
    compose_command --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" logs --tail 100 frontend || true
    return 1
  fi

  deadline=$((SECONDS + HEALTH_TIMEOUT_SECONDS))
  until curl --fail --silent --show-error "${HEALTH_URL}" >/dev/null; do
    if (( SECONDS >= deadline )); then
      echo "Backend health check timed out after ${HEALTH_TIMEOUT_SECONDS}s" >&2
      publish_deployment_health 0
      compose_command --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" ps || true
      compose_command --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" logs --tail 100 backend || true
      return 1
    fi
    sleep 3
  done

  publish_deployment_health 1

  compose_command --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" ps
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main "$@"
fi
