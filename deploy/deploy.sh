#!/usr/bin/env bash
set -Eeuo pipefail

APP_DIR=/opt/miriyum
COMPOSE_FILE="${APP_DIR}/docker-compose.prod.yml"
ENV_FILE="${APP_DIR}/.env"
HEALTH_URL=http://127.0.0.1:8080/actuator/health
HEALTH_TIMEOUT_SECONDS=90

: "${AWS_REGION:?AWS_REGION must be set}"
: "${BACKEND_IMAGE:?BACKEND_IMAGE must be set to an immutable ECR image tag}"

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "Missing runtime environment file: ${ENV_FILE}" >&2
  exit 1
fi

for command in aws curl docker; do
  command -v "${command}" >/dev/null
done

account_id=$(aws sts get-caller-identity --query Account --output text)
registry="${account_id}.dkr.ecr.${AWS_REGION}.amazonaws.com"

aws ecr get-login-password --region "${AWS_REGION}" \
  | docker login --username AWS --password-stdin "${registry}"

export BACKEND_IMAGE

docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" pull
docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" up -d --remove-orphans

deadline=$((SECONDS + HEALTH_TIMEOUT_SECONDS))
until curl --fail --silent --show-error "${HEALTH_URL}" >/dev/null; do
  if (( SECONDS >= deadline )); then
    echo "Backend health check timed out after ${HEALTH_TIMEOUT_SECONDS}s" >&2
    docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" ps
    docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" logs --tail 100 backend
    exit 1
  fi
  sleep 3
done

docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" ps
