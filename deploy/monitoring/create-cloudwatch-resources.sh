#!/usr/bin/env bash
# CloudWatch staging 알람·Dashboard·SNS 구독을 한 번만 생성한다.
set -Eeuo pipefail

: "${AWS_REGION:?AWS_REGION must be set}"
: "${EC2_INSTANCE_ID:?EC2_INSTANCE_ID must be set}"
: "${ALARM_EMAIL:?ALARM_EMAIL must be set}"

TOPIC_NAME="${TOPIC_NAME:-miriyum-staging-alerts}"
DASHBOARD_NAME="${DASHBOARD_NAME:-miriyum-staging}"
LOG_GROUP_NAME="${LOG_GROUP_NAME:-/miriyum/staging/docker}"
NAMESPACE="MiriYum/Staging"

command -v aws >/dev/null

log_group_count=$(aws logs describe-log-groups \
  --region "$AWS_REGION" \
  --log-group-name-prefix "$LOG_GROUP_NAME" \
  --query "length(logGroups[?logGroupName=='$LOG_GROUP_NAME'])" \
  --output text)

if [[ "$log_group_count" == "0" ]]; then
  aws logs create-log-group \
    --region "$AWS_REGION" \
    --log-group-name "$LOG_GROUP_NAME"
fi

aws logs put-retention-policy \
  --region "$AWS_REGION" \
  --log-group-name "$LOG_GROUP_NAME" \
  --retention-in-days 7

aws logs put-metric-filter \
  --region "$AWS_REGION" \
  --log-group-name "$LOG_GROUP_NAME" \
  --filter-name miriyum-staging-refresh-risk-event-delivery-stalled \
  --filter-pattern '"event=refresh_token_risk_event_delivery_stalled"' \
  --metric-transformations \
    "metricName=RefreshTokenRiskEventDeliveryStalled,metricNamespace=$NAMESPACE,metricValue=1,defaultValue=0"

aws logs put-metric-filter \
  --region "$AWS_REGION" \
  --log-group-name "$LOG_GROUP_NAME" \
  --filter-name miriyum-staging-refresh-risk-event-pending-count \
  --filter-pattern '[..., event=refresh_token_risk_event_pending_count, pending_count, ...]' \
  --metric-transformations \
    'metricName=RefreshTokenRiskEventPendingCount,metricNamespace='"$NAMESPACE"',metricValue=$pending_count'

topic_arn=$(aws sns create-topic \
  --region "$AWS_REGION" \
  --name "$TOPIC_NAME" \
  --query TopicArn \
  --output text)

subscription_count=$(aws sns list-subscriptions-by-topic \
  --region "$AWS_REGION" \
  --topic-arn "$topic_arn" \
  --query "length(Subscriptions[?Protocol=='email' && Endpoint=='$ALARM_EMAIL'])" \
  --output text)

if [[ "$subscription_count" == "0" ]]; then
  aws sns subscribe \
    --region "$AWS_REGION" \
    --topic-arn "$topic_arn" \
    --protocol email \
    --notification-endpoint "$ALARM_EMAIL" \
    >/dev/null
fi

put_alarm() {
  local name="$1"
  shift
  aws cloudwatch put-metric-alarm \
    --region "$AWS_REGION" \
    --alarm-name "$name" \
    --alarm-actions "$topic_arn" \
    --ok-actions "$topic_arn" \
    --treat-missing-data notBreaching \
    "$@"
}

put_alarm "miriyum-staging-cpu-high" \
  --namespace AWS/EC2 \
  --metric-name CPUUtilization \
  --dimensions "Name=InstanceId,Value=$EC2_INSTANCE_ID" \
  --statistic Average \
  --period 300 \
  --evaluation-periods 2 \
  --threshold 80 \
  --comparison-operator GreaterThanThreshold

put_alarm "miriyum-staging-status-check-failed" \
  --namespace AWS/EC2 \
  --metric-name StatusCheckFailed \
  --dimensions "Name=InstanceId,Value=$EC2_INSTANCE_ID" \
  --statistic Maximum \
  --period 60 \
  --evaluation-periods 1 \
  --threshold 0 \
  --comparison-operator GreaterThanThreshold

put_alarm "miriyum-staging-memory-high" \
  --namespace "$NAMESPACE" \
  --metric-name mem_used_percent \
  --dimensions "Name=InstanceId,Value=$EC2_INSTANCE_ID" \
  --statistic Average \
  --period 300 \
  --evaluation-periods 2 \
  --threshold 85 \
  --comparison-operator GreaterThanThreshold

put_alarm "miriyum-staging-disk-high" \
  --namespace "$NAMESPACE" \
  --metric-name disk_used_percent \
  --dimensions "Name=InstanceId,Value=$EC2_INSTANCE_ID" \
  --statistic Average \
  --period 300 \
  --evaluation-periods 2 \
  --threshold 85 \
  --comparison-operator GreaterThanThreshold

put_alarm "miriyum-staging-deployment-health-failed" \
  --namespace "$NAMESPACE" \
  --metric-name DeploymentHealth \
  --statistic Minimum \
  --period 300 \
  --evaluation-periods 1 \
  --threshold 0.5 \
  --comparison-operator LessThanThreshold

put_alarm "miriyum-staging-refresh-risk-event-delivery-stalled" \
  --namespace "$NAMESPACE" \
  --metric-name RefreshTokenRiskEventDeliveryStalled \
  --statistic Sum \
  --period 300 \
  --evaluation-periods 1 \
  --threshold 0 \
  --comparison-operator GreaterThanThreshold

dashboard_body=$(cat <<EOF
{
  "widgets": [
    {
      "type": "metric",
      "x": 0,
      "y": 0,
      "width": 12,
      "height": 6,
      "properties": {
        "view": "timeSeries",
        "region": "$AWS_REGION",
        "title": "MiriYum staging EC2",
        "period": 300,
        "stat": "Average",
        "metrics": [
          ["AWS/EC2", "CPUUtilization", "InstanceId", "$EC2_INSTANCE_ID"],
          ["MiriYum/Staging", "mem_used_percent", "InstanceId", "$EC2_INSTANCE_ID"],
          ["MiriYum/Staging", "disk_used_percent", "InstanceId", "$EC2_INSTANCE_ID"]
        ]
      }
    },
    {
      "type": "metric",
      "x": 0,
      "y": 6,
      "width": 12,
      "height": 6,
      "properties": {
        "view": "timeSeries",
        "region": "$AWS_REGION",
        "title": "MiriYum staging network",
        "period": 300,
        "stat": "Sum",
        "metrics": [
          ["AWS/EC2", "NetworkIn", "InstanceId", "$EC2_INSTANCE_ID"],
          ["AWS/EC2", "NetworkOut", "InstanceId", "$EC2_INSTANCE_ID"]
        ]
      }
    },
    {
      "type": "metric",
      "x": 12,
      "y": 12,
      "width": 12,
      "height": 6,
      "properties": {
        "view": "timeSeries",
        "region": "$AWS_REGION",
        "title": "MiriYum deployment health",
        "period": 300,
        "stat": "Minimum",
        "metrics": [
          ["MiriYum/Staging", "DeploymentHealth"]
        ]
      }
    },
    {
      "type": "metric",
      "x": 12,
      "y": 6,
      "width": 12,
      "height": 6,
      "properties": {
        "view": "timeSeries",
        "region": "$AWS_REGION",
        "title": "MiriYum pending refresh risk events",
        "period": 300,
        "stat": "Maximum",
        "metrics": [
          ["MiriYum/Staging", "RefreshTokenRiskEventPendingCount"]
        ]
      }
    }
  ]
}
EOF
)

aws cloudwatch put-dashboard \
  --region "$AWS_REGION" \
  --dashboard-name "$DASHBOARD_NAME" \
  --dashboard-body "$dashboard_body" \
  >/dev/null

printf 'Created or updated CloudWatch resources. Confirm the SNS email subscription before testing alarms.\n'
