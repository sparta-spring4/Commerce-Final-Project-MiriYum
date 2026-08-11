#!/usr/bin/env bash
# SSM이 EC2에서 실행한다. 변경 불가능한 ECR 이미지를 받고 127.0.0.1의 health를 확인한다.
set -Eeuo pipefail

APP_DIR=/opt/miriyum
COMPOSE_FILE="${APP_DIR}/docker-compose.prod.yml"
ENV_FILE="${APP_DIR}/.env"
HEALTH_URL=http://127.0.0.1:8080/actuator/health
HEALTH_TIMEOUT_SECONDS=90
CLOUDWATCH_NAMESPACE="${MIRIYUM_CLOUDWATCH_NAMESPACE:-MiriYum/Staging}"

: "${AWS_REGION:?AWS_REGION must be set}"
: "${BACKEND_IMAGE:?BACKEND_IMAGE must be set to an immutable ECR image tag}"

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "Missing runtime environment file: ${ENV_FILE}" >&2
  exit 1
fi

for command in aws curl docker; do
  command -v "${command}" >/dev/null
done

publish_deployment_health() {
  local value="$1"
  aws cloudwatch put-metric-data \
    --namespace "${CLOUDWATCH_NAMESPACE}" \
    --metric-data "MetricName=DeploymentHealth,Value=${value},Unit=Count" \
    >/dev/null || echo "Warning: CloudWatch deployment health metric was not published." >&2
}

# 인스턴스 역할이 배포 시 ECR 토큰을 받아오므로 레지스트리 비밀번호를 저장하지 않는다.
account_id=$(aws sts get-caller-identity --query Account --output text)
registry="${account_id}.dkr.ecr.${AWS_REGION}.amazonaws.com"

aws ecr get-login-password --region "${AWS_REGION}" \
  | docker login --username AWS --password-stdin "${registry}"

export BACKEND_IMAGE

# 실행 환경은 서버에만 두고 이미지와 배포 파일만 갱신한다.
docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" pull
docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" up -d --remove-orphans

deadline=$((SECONDS + HEALTH_TIMEOUT_SECONDS))
until curl --fail --silent --show-error "${HEALTH_URL}" >/dev/null; do
  if (( SECONDS >= deadline )); then
    echo "Backend health check timed out after ${HEALTH_TIMEOUT_SECONDS}s" >&2
    publish_deployment_health 0
    docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" ps
    docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" logs --tail 100 backend
    exit 1
  fi
  sleep 3
done

publish_deployment_health 1

docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" ps
