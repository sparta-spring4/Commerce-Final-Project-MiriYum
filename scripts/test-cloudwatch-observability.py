import json
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CONFIG_PATH = ROOT / "deploy" / "monitoring" / "cloudwatch-agent-config.json"
RESOURCE_SCRIPT_PATH = ROOT / "deploy" / "monitoring" / "create-cloudwatch-resources.sh"
WORKFLOW_PATH = ROOT / ".github" / "workflows" / "backend-cd.yml"
COMPOSE_PATH = ROOT / "deploy" / "docker-compose.prod.yml"
ENV_EXAMPLE_PATH = ROOT / "deploy" / ".env.example"
DEPLOY_SCRIPT_PATH = ROOT / "deploy" / "deploy.sh"
OBSERVABILITY_DOCUMENT_PATH = ROOT / "docs" / "deployment" / "cloudwatch-staging-observability.md"
GIT_BASH_EXECUTABLE = Path(r"C:\Program Files\Git\bin\bash.exe")
BASH_EXECUTABLE = str(GIT_BASH_EXECUTABLE) if GIT_BASH_EXECUTABLE.exists() else shutil.which("bash")
TEST_NOTIFICATION_HISTORY_CURSOR_SECRET = (
    "test-only-notification-history-cursor-secret"
)


class CloudWatchObservabilityConfigTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.config = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
        cls.resource_script = RESOURCE_SCRIPT_PATH.read_text(encoding="utf-8")
        cls.workflow = WORKFLOW_PATH.read_text(encoding="utf-8")
        cls.compose = COMPOSE_PATH.read_text(encoding="utf-8")
        cls.deploy_script = DEPLOY_SCRIPT_PATH.read_text(encoding="utf-8")
        cls.observability_document = OBSERVABILITY_DOCUMENT_PATH.read_text(encoding="utf-8")
        cls.compose_config = cls.load_compose_config(ENV_EXAMPLE_PATH)

    @staticmethod
    def load_compose_config(env_file):
        environment = CloudWatchObservabilityConfigTest.compose_environment()
        result = subprocess.run(
            [
                "docker",
                "compose",
                "--env-file",
                str(env_file),
                "-f",
                str(COMPOSE_PATH),
                "config",
                "--format",
                "json",
            ],
            cwd=ROOT,
            capture_output=True,
            check=True,
            env=environment,
            text=True,
        )
        return json.loads(result.stdout)

    @staticmethod
    def compose_environment():
        environment = os.environ.copy()
        environment["MIRIYUM_NOTIFICATION_HISTORY_CURSOR_SECRET"] = (
            TEST_NOTIFICATION_HISTORY_CURSOR_SECRET
        )
        return environment

    def test_disk_metric_has_instance_only_aggregation(self):
        metrics = self.config["metrics"]
        self.assertEqual([["InstanceId"]], metrics["aggregation_dimensions"])
        disk = metrics["metrics_collected"]["disk"]
        self.assertEqual(["used_percent"], disk["measurement"])
        self.assertEqual(["used_percent"], disk["drop_original_metrics"])

    def test_disk_alarm_uses_aggregated_instance_dimension(self):
        start = self.resource_script.index('put_alarm "miriyum-staging-disk-high"')
        end = self.resource_script.index('put_alarm "miriyum-staging-deployment-health-failed"')
        disk_alarm = self.resource_script[start:end]
        self.assertIn('Name=InstanceId,Value=$EC2_INSTANCE_ID', disk_alarm)

    def test_dashboard_uses_aggregated_instance_dimension(self):
        self.assertIn(
            '["MiriYum/Staging", "disk_used_percent", "InstanceId", "$EC2_INSTANCE_ID"]',
            self.resource_script,
        )

    def test_disk_alarm_uses_published_metric_name(self):
        start = self.resource_script.index('put_alarm "miriyum-staging-disk-high"')
        end = self.resource_script.index('put_alarm "miriyum-staging-deployment-health-failed"')
        disk_alarm = self.resource_script[start:end]
        self.assertIn("--metric-name disk_used_percent", disk_alarm)

    def test_cd_reloads_cloudwatch_agent_after_copying_config(self):
        self.assertIn("amazon-cloudwatch-agent-ctl -a fetch-config", self.workflow)
        self.assertIn("file:/opt/miriyum/monitoring/cloudwatch-agent.json", self.workflow)

    def test_compose_sends_each_service_log_to_a_dedicated_stream(self):
        expected_streams = ("mysql", "backend", "nginx", "valkey")
        for stream in expected_streams:
            self.assertIn("awslogs-stream: " + stream, self.compose)
        self.assertEqual(len(expected_streams), self.compose.count("driver: awslogs"))
        self.assertIn("awslogs-group: /miriyum/staging/docker", self.compose)
        self.assertNotIn("logs", self.config)
        self.assertNotIn("/var/lib/docker/containers/*", json.dumps(self.config))

    def test_observability_document_lists_all_docker_log_streams(self):
        self.assertIn("`mysql`, `backend`, `nginx`, `valkey`", self.observability_document)

    def test_valkey_uses_a_backend_only_internal_network(self):
        services = self.compose_config["services"]

        self.assertEqual({"app", "backend-valkey"}, set(services["backend"]["networks"]))
        self.assertEqual({"backend-valkey"}, set(services["valkey"]["networks"]))
        self.assertEqual({"app"}, set(services["mysql"]["networks"]))
        self.assertEqual({"app"}, set(services["nginx"]["networks"]))
        self.assertTrue(self.compose_config["networks"]["backend-valkey"]["internal"])
        self.assertNotIn("ports", services["valkey"])

    def test_staging_enables_refresh_risk_event_delivery_explicitly(self):
        backend_environment = self.compose_config["services"]["backend"]["environment"]

        self.assertEqual("true", backend_environment["MIRIYUM_REFRESH_RISK_EVENT_DELIVERY_ENABLED"])

    def test_staging_can_enable_waiting_closure_worker_through_env_file(self):
        staging_environment = ENV_EXAMPLE_PATH.read_text(encoding="utf-8").replace(
            "MIRIYUM_WAITING_CLOSURE_ENABLED=false",
            "MIRIYUM_WAITING_CLOSURE_ENABLED=true",
        )
        with tempfile.NamedTemporaryFile(
            mode="w", encoding="utf-8", suffix=".env", delete=False
        ) as env_file:
            env_file.write(staging_environment)
            env_path = Path(env_file.name)

        try:
            compose_config = self.load_compose_config(env_path)
        finally:
            env_path.unlink(missing_ok=True)

        backend_environment = compose_config["services"]["backend"]["environment"]
        self.assertEqual(
            "true",
            backend_environment.get("MIRIYUM_WAITING_CLOSURE_ENABLED"),
        )

    def test_valkey_preserves_auth_state_with_aof_and_noeviction(self):
        valkey = self.compose_config["services"]["valkey"]
        self.assertEqual(
            [
                "/bin/sh",
                "-ec",
                "exec valkey-server \\\n"
                "  --appendonly yes \\\n"
                "  --appendfsync everysec \\\n"
                "  --maxmemory 128mb \\\n"
                "  --maxmemory-policy noeviction \\\n"
                '  --requirepass "$$MIRIYUM_VALKEY_PASSWORD"\n',
            ],
            valkey["command"],
        )
        self.assertEqual(
            [("volume", "valkey-data", "/data", False)],
            [
                (
                    volume["type"],
                    volume["source"],
                    volume["target"],
                    volume.get("read_only", False),
                )
                for volume in valkey["volumes"]
            ],
        )
        self.assertIn("valkey-data", self.compose_config["volumes"])

    def test_valkey_password_is_required_during_compose_config(self):
        env_lines = ENV_EXAMPLE_PATH.read_text(encoding="utf-8").splitlines()
        without_password = "\n".join(
            line for line in env_lines if not line.startswith("MIRIYUM_VALKEY_PASSWORD=")
        )

        with tempfile.NamedTemporaryFile(
            mode="w", encoding="utf-8", suffix=".env", delete=False
        ) as env_file:
            env_file.write(without_password)
            env_path = Path(env_file.name)

        environment = self.compose_environment()
        environment.pop("MIRIYUM_VALKEY_PASSWORD", None)
        try:
            result = subprocess.run(
                [
                    "docker",
                    "compose",
                    "--env-file",
                    str(env_path),
                    "-f",
                    str(COMPOSE_PATH),
                    "config",
                    "--quiet",
                ],
                cwd=ROOT,
                capture_output=True,
                env=environment,
                text=True,
            )
        finally:
            env_path.unlink(missing_ok=True)

        self.assertNotEqual(0, result.returncode)
        self.assertIn("MIRIYUM_VALKEY_PASSWORD is required", result.stderr)

    def test_valkey_healthcheck_rejects_unauthenticated_ping_and_accepts_authenticated_ping(self):
        healthcheck = self.compose_config["services"]["valkey"]["healthcheck"]["test"]

        self.assertEqual(
            [
                "CMD-SHELL",
                'valkey-cli ping 2>&1 | grep -q NOAUTH && '
                'REDISCLI_AUTH="$$MIRIYUM_VALKEY_PASSWORD" '
                "valkey-cli ping | grep -qx PONG",
            ],
            healthcheck,
        )

    def test_deployment_waits_for_valkey_startup_before_runtime_verification(self):
        self.assertIn(
            'VALKEY_HEALTH_TIMEOUT_SECONDS="${VALKEY_HEALTH_TIMEOUT_SECONDS:-60}"',
            self.deploy_script,
        )
        self.assertIn("wait_for_valkey_health()", self.deploy_script)
        self.assertIn('ps -q valkey', self.deploy_script)
        self.assertIn('"${health}" == "healthy"', self.deploy_script)
        self.assertIn("while :; do", self.deploy_script)
        self.assertIn("sleep 2", self.deploy_script)

    def test_deployment_fails_when_valkey_health_wait_times_out(self):
        self.assertIn("verify_valkey()", self.deploy_script)
        self.assertIn("Valkey health check timed out after", self.deploy_script)
        self.assertIn("last status:", self.deploy_script)
        self.assertIn("publish_deployment_health 0", self.deploy_script)
        self.assertIn("Unauthenticated Valkey ping did not return NOAUTH.", self.deploy_script)
        self.assertIn('grep -qx PONG', self.deploy_script)
        self.assertIn('port valkey 6379', self.deploy_script)
        self.assertIn('docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" logs --tail 100 valkey', self.deploy_script)

    def test_deployment_backfills_existing_pending_risk_markers_before_scan_is_removed(self):
        function_start = self.deploy_script.index("backfill_pending_risk_event_index()")
        function_end = self.deploy_script.index(
            "# 인스턴스 역할이 배포 시 ECR 토큰을 받아오므로",
            function_start,
        )
        backfill_function = self.deploy_script[function_start:function_end]

        self.assertIn("auth:risk:pending:*", backfill_function)
        self.assertIn("auth:risk:pending-index", backfill_function)
        self.assertIn("SADD", backfill_function)
        self.assertNotIn("backfill-v1", backfill_function)

    def test_deployment_repeats_backfill_after_legacy_rollback(self):
        with tempfile.TemporaryDirectory() as directory:
            temporary_path = Path(directory)
            marker_path = self.to_bash_path(temporary_path / "indexed-markers")
            scan_count_path = self.to_bash_path(temporary_path / "scan-count")
            bin_path = temporary_path / "bin"
            bin_path.mkdir()
            valkey_cli = bin_path / "valkey-cli"
            valkey_cli.write_text(
                """#!/usr/bin/env bash
if [[ "$1" == "--raw" ]]; then
  shift
fi

case "$1" in
  SCAN)
    count=0
    [[ -f "$RISK_SCAN_COUNT_PATH" ]] && count=$(cat "$RISK_SCAN_COUNT_PATH")
    count=$((count + 1))
    printf '%s\\n' "$count" > "$RISK_SCAN_COUNT_PATH"

    if [[ "$count" -eq 1 ]]; then
      printf '0\\nauth:risk:pending:before-rollback\\n'
    else
      printf '0\\nauth:risk:pending:legacy-rollback\\n'
    fi
    ;;
  SADD)
    printf '%s\\n' "$3" >> "$RISK_MARKER_TEST_PATH"
    echo 1
    ;;
  *)
    exit 1
    ;;
esac
""",
                encoding="utf-8",
            )
            valkey_cli.chmod(0o755)
            result = self.run_deploy_script(
                """
PATH="$TEST_VALKEY_BIN:$PATH"
export PATH

docker() {
  local arguments=("$@")
  local index

  for ((index = 0; index < ${#arguments[@]} - 2; index++)); do
    if [[ "${arguments[index]}" == "sh" && "${arguments[index + 1]}" == "-ec" ]]; then
      bash -ec "${arguments[index + 2]}" "${arguments[@]:index + 3}"
      return
    fi
  done
  return 1
}
backfill_pending_risk_event_index
# 구버전으로 롤백된 동안 새 marker가 생긴 뒤 다시 전진 배포한 상황을 재현한다.
backfill_pending_risk_event_index
""",
                {
                    "RISK_MARKER_TEST_PATH": marker_path,
                    "RISK_SCAN_COUNT_PATH": scan_count_path,
                    "TEST_VALKEY_BIN": self.to_bash_path(bin_path),
                },
            )

            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertEqual(
                [
                    "auth:risk:pending:before-rollback",
                    "auth:risk:pending:legacy-rollback",
                ],
                Path(directory, "indexed-markers").read_text(encoding="utf-8").splitlines(),
            )

    def test_valkey_health_wait_accepts_starting_then_healthy(self):
        with tempfile.TemporaryDirectory() as directory:
            temporary_path = Path(directory)
            state_path = temporary_path / "inspect-count"
            result = self.run_deploy_script(
                """
docker() {
  if [[ "$1" == "compose" ]]; then
    echo valkey-container
    return 0
  fi
  if [[ "$1" == "inspect" ]]; then
    local count=0
    [[ -f "$VALKEY_TEST_STATE" ]] && count=$(cat "$VALKEY_TEST_STATE")
    count=$((count + 1))
    echo "$count" > "$VALKEY_TEST_STATE"
    [[ "$count" -eq 1 ]] && echo starting || echo healthy
    return 0
  fi
  return 1
}
sleep() { :; }
wait_for_valkey_health
""",
                {
                    "VALKEY_HEALTH_TIMEOUT_SECONDS": "5",
                    "VALKEY_TEST_STATE": self.to_bash_path(state_path),
                },
            )

            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual("2", state_path.read_text(encoding="utf-8").strip())

    def test_valkey_verification_fails_when_authenticated_ping_does_not_return_pong(self):
        result = self.run_deploy_script(
            """
docker() {
  if [[ "$1" == "compose" ]]; then
    if [[ "$*" == *"ps -q valkey"* ]]; then
      echo valkey-container
    elif [[ "$*" == *"sh -ec"* ]]; then
      echo "simulated authenticated ping failure" >&2
      return 1
    elif [[ "$*" == *"valkey-cli ping"* ]]; then
      echo "NOAUTH Authentication required."
    fi
    return 0
  fi
  if [[ "$1" == "inspect" ]]; then
    echo healthy
    return 0
  fi
  return 1
}
if verify_valkey; then
  exit 0
fi
exit 1
""",
            {"VALKEY_HEALTH_TIMEOUT_SECONDS": "0"},
        )

        self.assertNotEqual(0, result.returncode)

    def test_main_publishes_failed_health_when_valkey_never_starts(self):
        with tempfile.TemporaryDirectory() as directory:
            temporary_path = Path(directory)
            metric_path = temporary_path / "metric-arguments"
            environment_file = temporary_path / ".env"
            environment_file.write_text("placeholder=true\n", encoding="utf-8")
            result = self.run_deploy_script(
                """
aws() {
  if [[ "$1 $2" == "sts get-caller-identity" ]]; then
    echo 123456789012
  elif [[ "$1 $2" == "ecr get-login-password" ]]; then
    echo token
  elif [[ "$1 $2" == "cloudwatch put-metric-data" ]]; then
    printf '%s\\n' "$@" > "$VALKEY_TEST_METRIC"
  fi
  return 0
}
docker() {
  if [[ "$1" == "login" ]]; then
    cat >/dev/null
  fi
  return 0
}
curl() { return 1; }
sleep() { :; }
main
""",
                {
                    "AWS_REGION": "ap-northeast-2",
                    "BACKEND_IMAGE": "example.invalid/backend:sha",
                    "ENV_FILE": self.to_bash_path(environment_file),
                    "COMPOSE_FILE": self.to_bash_path(temporary_path / "docker-compose.yml"),
                    "VALKEY_HEALTH_TIMEOUT_SECONDS": "0",
                    "VALKEY_TEST_METRIC": self.to_bash_path(metric_path),
                },
            )

            self.assertNotEqual(0, result.returncode)
            self.assertIn("Value=0", metric_path.read_text(encoding="utf-8"))

    @staticmethod
    def run_deploy_script(script, extra_environment):
        environment = os.environ.copy()
        environment.update(extra_environment)
        return subprocess.run(
            [
                BASH_EXECUTABLE,
                "-c",
                f"source <(tr -d '\\r' < deploy/deploy.sh); {script}",
            ],
            cwd=ROOT,
            capture_output=True,
            env=environment,
            text=True,
            encoding="utf-8",
            errors="replace",
        )

    @staticmethod
    def to_bash_path(path):
        normalized = Path(path).resolve().as_posix()
        if len(normalized) >= 3 and normalized[1:3] == ":/":
            return f"/{normalized[0].lower()}{normalized[2:]}"
        return normalized


    def test_dashboard_includes_ec2_network_metrics(self):
        self.assertIn('["AWS/EC2", "NetworkIn", "InstanceId", "$EC2_INSTANCE_ID"]', self.resource_script)
        self.assertIn('["AWS/EC2", "NetworkOut", "InstanceId", "$EC2_INSTANCE_ID"]', self.resource_script)

    def test_refresh_risk_delivery_stall_log_becomes_a_cloudwatch_metric(self):
        self.assertIn("aws logs put-metric-filter", self.resource_script)
        self.assertIn('event=refresh_token_risk_event_delivery_stalled', self.resource_script)
        self.assertIn('metricName=RefreshTokenRiskEventDeliveryStalled', self.resource_script)

    def test_refresh_risk_delivery_stall_metric_has_an_alarm(self):
        start = self.resource_script.index(
            'put_alarm "miriyum-staging-refresh-risk-event-delivery-stalled"'
        )
        alarm = self.resource_script[start:]
        self.assertIn("--metric-name RefreshTokenRiskEventDeliveryStalled", alarm)
        self.assertIn("--statistic Sum", alarm)
        self.assertIn("--threshold 0", alarm)
        self.assertIn("--comparison-operator GreaterThanThreshold", alarm)


if __name__ == "__main__":
    unittest.main()
