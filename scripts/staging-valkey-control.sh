#!/usr/bin/env bash
set -Eeuo pipefail

readonly COMPOSE_ENV_FILE="/opt/miriyum/.env"
readonly COMPOSE_FILE="/opt/miriyum/docker-compose.prod.yml"
readonly INTERRUPTION_SECONDS=10
readonly HEALTH_ATTEMPTS=30
readonly HEALTH_INTERVAL_SECONDS=2

compose() {
  docker compose --env-file "${COMPOSE_ENV_FILE}" -f "${COMPOSE_FILE}" "$@"
}

wait_for_valkey_health() {
  local attempt container_id health

  for ((attempt = 1; attempt <= HEALTH_ATTEMPTS; attempt++)); do
    container_id="$(compose ps -q valkey)"
    if [[ -n "${container_id}" ]]; then
      health="$(docker inspect --format '{{.State.Health.Status}}' "${container_id}" 2>/dev/null || true)"
      if [[ "${health}" == "healthy" ]]; then
        return 0
      fi
    fi
    sleep "${HEALTH_INTERVAL_SECONDS}"
  done

  echo "Valkey did not become healthy within the fixed recovery window." >&2
  return 1
}

start_and_verify_valkey() {
  compose up -d --no-deps valkey
  wait_for_valkey_health
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
    wait_for_valkey_health
    trap recover_on_exit EXIT
    trap 'exit 129' HUP
    trap 'exit 130' INT
    trap 'exit 143' TERM

    compose stop valkey
    echo "event=staging_valkey_interruption_started duration_seconds=${INTERRUPTION_SECONDS}"
    sleep "${INTERRUPTION_SECONDS}"
    start_and_verify_valkey

    trap - EXIT HUP INT TERM
    echo "event=staging_valkey_interruption_completed duration_seconds=${INTERRUPTION_SECONDS} health=healthy"
    ;;
  recover)
    start_and_verify_valkey
    echo "event=staging_valkey_recovery_completed health=healthy"
    ;;
  *)
    echo "Unsupported staging Valkey control action." >&2
    exit 2
    ;;
esac
