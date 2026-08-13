#!/usr/bin/env bash
# SSM이 EC2에서 실행한다. 변경 불가능한 ECR 이미지를 받고 127.0.0.1의 health를 확인한다.
set -Eeuo pipefail

APP_DIR="${APP_DIR:-/opt/miriyum}"
COMPOSE_FILE="${COMPOSE_FILE:-${APP_DIR}/docker-compose.prod.yml}"
ENV_FILE="${ENV_FILE:-${APP_DIR}/.env}"
HEALTH_URL="${HEALTH_URL:-http://127.0.0.1:8080/actuator/health}"
HEALTH_TIMEOUT_SECONDS="${HEALTH_TIMEOUT_SECONDS:-90}"
VALKEY_HEALTH_TIMEOUT_SECONDS="${VALKEY_HEALTH_TIMEOUT_SECONDS:-60}"
CLOUDWATCH_NAMESPACE="${MIRIYUM_CLOUDWATCH_NAMESPACE:-MiriYum/Staging}"

publish_deployment_health() {
  local value="$1"
  aws cloudwatch put-metric-data \
    --namespace "${CLOUDWATCH_NAMESPACE}" \
    --metric-data "MetricName=DeploymentHealth,Value=${value},Unit=Count" \
    >/dev/null || echo "Warning: CloudWatch deployment health metric was not published." >&2
}

wait_for_valkey_health() {
  local compose=(docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}")
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
  local compose=(docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}")
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

# 구버전 롤백 중 생성된 marker까지 다음 전달 대상에서 누락되지 않게 매 배포 이관한다.
backfill_pending_risk_event_index() {
  local compose=(docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}")
  local marker_pattern="auth:risk:pending:*"
  local pending_index="auth:risk:pending-index"

  "${compose[@]}" exec -T valkey sh -ec '
    marker_pattern="$1"
    pending_index="$2"
    cursor=0

    while :; do
      scan_result="$(REDISCLI_AUTH="$MIRIYUM_VALKEY_PASSWORD" valkey-cli --raw SCAN "$cursor" MATCH "$marker_pattern" COUNT 100)"
      cursor="$(printf "%s\\n" "$scan_result" | sed -n "1p")"
      printf "%s\\n" "$scan_result" | tail -n +2 | while IFS= read -r marker_key; do
        [ -n "$marker_key" ] || continue
        REDISCLI_AUTH="$MIRIYUM_VALKEY_PASSWORD" valkey-cli SADD "$pending_index" "$marker_key" >/dev/null
      done

      [ "$cursor" = "0" ] && break
    done
  ' sh "${marker_pattern}" "${pending_index}"
}

# 인스턴스 역할이 배포 시 ECR 토큰을 받아오므로 레지스트리 비밀번호를 저장하지 않는다.
main() {
  local account_id registry deadline

  : "${AWS_REGION:?AWS_REGION must be set}"
  : "${BACKEND_IMAGE:?BACKEND_IMAGE must be set to an immutable ECR image tag}"

  if [[ ! -f "${ENV_FILE}" ]]; then
    echo "Missing runtime environment file: ${ENV_FILE}" >&2
    return 1
  fi

  for command in aws curl docker; do
    command -v "${command}" >/dev/null
  done

  account_id=$(aws sts get-caller-identity --query Account --output text)
  registry="${account_id}.dkr.ecr.${AWS_REGION}.amazonaws.com"

  aws ecr get-login-password --region "${AWS_REGION}" \
    | docker login --username AWS --password-stdin "${registry}"

  export BACKEND_IMAGE

# 실행 환경은 서버에만 두고 이미지와 배포 파일만 갱신한다.
  docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" pull
  docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" up -d --remove-orphans

  if ! verify_valkey; then
    publish_deployment_health 0
    docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" ps
    docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" logs --tail 100 valkey
    return 1
  fi

  backfill_pending_risk_event_index

  deadline=$((SECONDS + HEALTH_TIMEOUT_SECONDS))
  until curl --fail --silent --show-error "${HEALTH_URL}" >/dev/null; do
    if (( SECONDS >= deadline )); then
      echo "Backend health check timed out after ${HEALTH_TIMEOUT_SECONDS}s" >&2
      publish_deployment_health 0
      docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" ps
      docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" logs --tail 100 backend
      return 1
    fi
    sleep 3
  done

  publish_deployment_health 1

  docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" ps
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main "$@"
fi
