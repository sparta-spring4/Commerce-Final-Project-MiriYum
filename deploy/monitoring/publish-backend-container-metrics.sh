#!/usr/bin/env bash
# Staging 부하 검증은 EC2 전체가 아니라 backend 컨테이너의 실제 자원 사용량을 기준으로 한다.
set -Eeuo pipefail

AWS_BIN="${AWS_BIN:-aws}"
CURL_BIN="${CURL_BIN:-curl}"
DOCKER_BIN="${DOCKER_BIN:-docker}"
AWS_REGION="${AWS_REGION:?AWS_REGION must be set}"
COMPOSE_FILE="${COMPOSE_FILE:-/opt/miriyum/docker-compose.prod.yml}"
ENV_FILE="${ENV_FILE:-/opt/miriyum/.env}"
NAMESPACE="${CLOUDWATCH_NAMESPACE:-MiriYum/Staging}"

metadata_token="$($CURL_BIN --fail --silent --show-error --request PUT \
  --header 'X-aws-ec2-metadata-token-ttl-seconds: 21600' \
  http://169.254.169.254/latest/api/token)"
instance_id="$($CURL_BIN --fail --silent --show-error \
  --header "X-aws-ec2-metadata-token: $metadata_token" \
  http://169.254.169.254/latest/meta-data/instance-id)"

publish_failure() {
  echo 'event=backend_container_metrics_collection_failed' >&2
  "$AWS_BIN" cloudwatch put-metric-data \
    --region "$AWS_REGION" \
    --namespace "$NAMESPACE" \
    --metric-data "MetricName=BackendContainerMetricsCollectionFailure,Value=1,Unit=Count,Dimensions=[{Name=InstanceId,Value=$instance_id}]" \
    >/dev/null || true
}

parse_percent() {
  local value="$1"
  if [[ ! "$value" =~ ^[0-9]+([.][0-9]+)?%$ ]]; then
    return 1
  fi
  printf '%s\n' "${value%\%}"
}

parse_bytes() {
  local value="$1" amount unit multiplier
  if [[ ! "$value" =~ ^([0-9]+([.][0-9]+)?)(B|kB|KB|KiB|MB|MiB|GB|GiB|TB|TiB)$ ]]; then
    return 1
  fi

  amount="${BASH_REMATCH[1]}"
  unit="${BASH_REMATCH[3]}"
  case "$unit" in
    B) multiplier=1 ;;
    kB|KB) multiplier=1000 ;;
    KiB) multiplier=1024 ;;
    MB) multiplier=1000000 ;;
    MiB) multiplier=1048576 ;;
    GB) multiplier=1000000000 ;;
    GiB) multiplier=1073741824 ;;
    TB) multiplier=1000000000000 ;;
    TiB) multiplier=1099511627776 ;;
    *) return 1 ;;
  esac
  awk -v amount="$amount" -v multiplier="$multiplier" 'BEGIN { printf "%.0f\n", amount * multiplier }'
}

container_id="$($DOCKER_BIN compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" ps -q backend)"
if [[ -z "$container_id" ]]; then
  publish_failure
  exit 1
fi

if ! stats="$($DOCKER_BIN stats --no-stream --format '{{.CPUPerc}}|{{.MemUsage}}|{{.MemPerc}}' "$container_id")"; then
  publish_failure
  exit 1
fi

stats="${stats//$'\r'/}"
IFS='|' read -r cpu_percent memory_usage memory_percent <<<"$stats"
memory_used="${memory_usage%% / *}"

if ! cpu_value="$(parse_percent "$cpu_percent")" \
  || ! memory_value="$(parse_bytes "$memory_used")" \
  || ! memory_percent_value="$(parse_percent "$memory_percent")"; then
  publish_failure
  exit 1
fi

if ! "$AWS_BIN" cloudwatch put-metric-data \
  --region "$AWS_REGION" \
  --namespace "$NAMESPACE" \
  --metric-data \
    "MetricName=BackendContainerCpuUtilizationPercent,Value=$cpu_value,Unit=Percent,Dimensions=[{Name=InstanceId,Value=$instance_id}]" \
    "MetricName=BackendContainerMemoryUsageBytes,Value=$memory_value,Unit=Bytes,Dimensions=[{Name=InstanceId,Value=$instance_id}]" \
    "MetricName=BackendContainerMemoryUtilizationPercent,Value=$memory_percent_value,Unit=Percent,Dimensions=[{Name=InstanceId,Value=$instance_id}]" \
    "MetricName=BackendContainerMetricsHeartbeat,Value=1,Unit=Count,Dimensions=[{Name=InstanceId,Value=$instance_id}]" \
    >/dev/null; then
  publish_failure
  exit 1
fi

echo "event=backend_container_metrics_collected cpu_percent=$cpu_value memory_usage_bytes=$memory_value memory_percent=$memory_percent_value"
