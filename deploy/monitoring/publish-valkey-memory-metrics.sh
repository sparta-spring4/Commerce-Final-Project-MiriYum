#!/usr/bin/env bash
# Auth Valkey의 메모리 한계를 넘기기 전에 수치를 관측한다. 실패를 정상 0%로 바꾸지 않는다.
set -Eeuo pipefail

AWS_BIN="${AWS_BIN:-aws}"
CURL_BIN="${CURL_BIN:-curl}"
DOCKER_BIN="${DOCKER_BIN:-docker}"
AWS_REGION="${AWS_REGION:?AWS_REGION must be set}"
COMPOSE_FILE="${COMPOSE_FILE:-/opt/miriyum/docker-compose.prod.yml}"
ENV_FILE="${ENV_FILE:-/opt/miriyum/.env}"
NAMESPACE="${CLOUDWATCH_NAMESPACE:-MiriYum/Staging}"

: "${MIRIYUM_VALKEY_PASSWORD:?MIRIYUM_VALKEY_PASSWORD must be set}"

metadata_token="$($CURL_BIN --fail --silent --show-error --request PUT \
  --header 'X-aws-ec2-metadata-token-ttl-seconds: 21600' \
  http://169.254.169.254/latest/api/token)"
instance_id="$($CURL_BIN --fail --silent --show-error \
  --header "X-aws-ec2-metadata-token: $metadata_token" \
  http://169.254.169.254/latest/meta-data/instance-id)"

publish_failure() {
  echo 'event=auth_valkey_memory_collection_failed' >&2
  "$AWS_BIN" cloudwatch put-metric-data \
    --region "$AWS_REGION" \
    --namespace "$NAMESPACE" \
    --metric-data "MetricName=AuthValkeyMemoryCollectionFailure,Value=1,Unit=Count,Dimensions=[{Name=InstanceId,Value=$instance_id}]" \
    >/dev/null || true
}

if ! memory_info="$(REDISCLI_AUTH="$MIRIYUM_VALKEY_PASSWORD" \
  "$DOCKER_BIN" compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" exec -T valkey \
  sh -ec 'REDISCLI_AUTH="$MIRIYUM_VALKEY_PASSWORD" valkey-cli --raw INFO memory')"; then
  publish_failure
  exit 1
fi

# valkey-cli emits CRLF on some hosts; parse the same values on every host.
memory_info="${memory_info//$'\r'/}"
used_memory="$(printf '%s\n' "$memory_info" | sed -n 's/^used_memory:\([0-9][0-9]*\)$/\1/p')"
maxmemory="$(printf '%s\n' "$memory_info" | sed -n 's/^maxmemory:\([0-9][0-9]*\)$/\1/p')"

if [[ -z "$used_memory" || -z "$maxmemory" || "$maxmemory" -le 0 ]]; then
  publish_failure
  exit 1
fi

utilization_percent="$(awk -v used="$used_memory" -v maximum="$maxmemory" \
  'BEGIN { printf "%.2f", (used / maximum) * 100 }')"

if ! "$AWS_BIN" cloudwatch put-metric-data \
  --region "$AWS_REGION" \
  --namespace "$NAMESPACE" \
  --metric-data \
    "MetricName=AuthValkeyUsedMemoryBytes,Value=$used_memory,Unit=Bytes,Dimensions=[{Name=InstanceId,Value=$instance_id}]" \
    "MetricName=AuthValkeyMaxMemoryBytes,Value=$maxmemory,Unit=Bytes,Dimensions=[{Name=InstanceId,Value=$instance_id}]" \
    "MetricName=AuthValkeyMemoryUtilizationPercent,Value=$utilization_percent,Unit=Percent,Dimensions=[{Name=InstanceId,Value=$instance_id}]" \
    "MetricName=AuthValkeyMemoryCollectionHeartbeat,Value=1,Unit=Count,Dimensions=[{Name=InstanceId,Value=$instance_id}]" \
    >/dev/null; then
  publish_failure
  exit 1
fi

echo "event=auth_valkey_memory_collected used_memory_bytes=$used_memory maxmemory_bytes=$maxmemory utilization_percent=$utilization_percent"
