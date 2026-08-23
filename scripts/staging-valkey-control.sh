#!/usr/bin/env bash
set -Eeuo pipefail

readonly COMPOSE_ENV_FILE="/opt/miriyum/.env"
readonly COMPOSE_FILE="/opt/miriyum/docker-compose.prod.yml"
readonly INTERRUPTION_SECONDS=10
readonly HEALTH_ATTEMPTS=30
readonly HEALTH_INTERVAL_SECONDS=2
readonly MIN_SCHEDULE_LEAD_SECONDS=5
readonly MAX_SCHEDULE_LEAD_SECONDS=60

RUNTIME_BACKEND_IMAGE=""
RUNTIME_FRONTEND_IMAGE=""
SCHEDULE_WAIT_SECONDS=0

compose() {
  BACKEND_IMAGE="${RUNTIME_BACKEND_IMAGE}" \
    FRONTEND_IMAGE="${RUNTIME_FRONTEND_IMAGE}" \
    docker compose --env-file "${COMPOSE_ENV_FILE}" -f "${COMPOSE_FILE}" "$@"
}

emit_failure() {
  local phase="$1" reason="$2" exit_code="$3"
  printf 'event=staging_valkey_control_failed action=%s phase=%s reason=%s exit_code=%s\n' \
    "${VALKEY_CONTROL_ACTION:-unknown}" "${phase}" "${reason}" "${exit_code}" >&2
}

run_compose() {
  local phase="$1" reason="$2" status
  shift 2

  if compose "$@" >/dev/null 2>&1; then
    return 0
  else
    status=$?
    emit_failure "${phase}" "${reason}" "${status}"
    return "${status}"
  fi
}

resolve_runtime_image() {
  local service="$1" target_variable="$2" container_id image status

  if container_id="$(docker ps -q \
    --filter "label=com.docker.compose.project=miriyum" \
    --filter "label=com.docker.compose.service=${service}" \
    --filter "label=com.docker.compose.oneoff=False" 2>/dev/null)"; then
    :
  else
    status=$?
    return "${status}"
  fi
  if [[ -z "${container_id}" || "${container_id}" == *$'\n'* ]]; then
    return 1
  fi

  if image="$(docker inspect --format '{{.Config.Image}}' "${container_id}" 2>/dev/null)"; then
    :
  else
    status=$?
    return "${status}"
  fi
  if [[ -z "${image}" || "${image}" == *$'\n'* ]]; then
    return 1
  fi

  printf -v "${target_variable}" '%s' "${image}"
}

bind_runtime_images() {
  local status

  if resolve_runtime_image backend RUNTIME_BACKEND_IMAGE \
    && resolve_runtime_image frontend RUNTIME_FRONTEND_IMAGE; then
    return 0
  else
    status=$?
    emit_failure preflight runtime-image-binding-failed "${status}"
    return "${status}"
  fi
}

validate_compose_contract() {
  local status

  if compose config --quiet >/dev/null 2>&1; then
    return 0
  else
    status=$?
    emit_failure preflight compose-config-invalid "${status}"
    return "${status}"
  fi
}

wait_for_valkey_health() {
  local attempt container_id health status

  for ((attempt = 1; attempt <= HEALTH_ATTEMPTS; attempt++)); do
    if container_id="$(compose ps -q valkey 2>/dev/null)"; then
      :
    else
      status=$?
      emit_failure health-check compose-ps-failed "${status}"
      return "${status}"
    fi
    if [[ -n "${container_id}" ]]; then
      health="$(docker inspect --format '{{.State.Health.Status}}' "${container_id}" 2>/dev/null || true)"
      if [[ "${health}" == "healthy" ]]; then
        return 0
      fi
    fi
    sleep "${HEALTH_INTERVAL_SECONDS}"
  done

  emit_failure health-check valkey-health-timeout 1
  return 1
}

start_and_verify_valkey() {
  run_compose start compose-up-failed up -d --no-deps valkey
  wait_for_valkey_health
}

validate_interrupt_schedule() {
  local execute_at="${VALKEY_CONTROL_EXECUTE_AT_EPOCH:-}" now delta

  if [[ ! "${execute_at}" =~ ^[0-9]{10}$ ]]; then
    emit_failure preflight invalid-scheduled-epoch 2
    return 2
  fi
  now="$(date +%s)"
  delta=$((execute_at - now))
  if ((delta < MIN_SCHEDULE_LEAD_SECONDS || delta > MAX_SCHEDULE_LEAD_SECONDS)); then
    emit_failure preflight invalid-scheduled-epoch 2
    return 2
  fi
  SCHEDULE_WAIT_SECONDS="${delta}"
}

reject_recovery_schedule() {
  if [[ -n "${VALKEY_CONTROL_EXECUTE_AT_EPOCH:-}" ]]; then
    emit_failure preflight unexpected-scheduled-epoch 2
    return 2
  fi
}

recover_on_exit() {
  local original_status=$? recovery_status=0
  trap - EXIT HUP INT TERM

  start_and_verify_valkey || recovery_status=$?
  if [[ "${recovery_status}" -ne 0 ]]; then
    echo "Valkey automatic recovery failed." >&2
    if [[ "${original_status}" -eq 0 ]]; then
      original_status="${recovery_status}"
    fi
  fi
  exit "${original_status}"
}

case "${VALKEY_CONTROL_ACTION:-}" in
  interrupt)
    validate_interrupt_schedule
    bind_runtime_images
    validate_compose_contract
    wait_for_valkey_health
    trap recover_on_exit EXIT
    trap 'exit 129' HUP
    trap 'exit 130' INT
    trap 'exit 143' TERM

    if sleep "${SCHEDULE_WAIT_SECONDS}"; then
      :
    else
      status=$?
      emit_failure preflight scheduled-wait-failed "${status}"
      exit "${status}"
    fi
    run_compose stop compose-stop-failed stop valkey
    echo "event=staging_valkey_interruption_started duration_seconds=${INTERRUPTION_SECONDS}"
    if sleep "${INTERRUPTION_SECONDS}"; then
      :
    else
      status=$?
      emit_failure interrupt interruption-wait-failed "${status}"
      exit "${status}"
    fi
    start_and_verify_valkey

    trap - EXIT HUP INT TERM
    echo "event=staging_valkey_interruption_completed duration_seconds=${INTERRUPTION_SECONDS} health=healthy"
    ;;
  recover)
    reject_recovery_schedule
    bind_runtime_images
    validate_compose_contract
    start_and_verify_valkey
    echo "event=staging_valkey_recovery_completed health=healthy"
    ;;
  *)
    echo "Unsupported staging Valkey control action." >&2
    exit 2
    ;;
esac
