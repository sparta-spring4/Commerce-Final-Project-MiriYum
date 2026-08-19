import json
import os
import re
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
NGINX_SELECTOR_PATH = (
    ROOT / "deploy" / "nginx" / "entrypoint" / "40-select-server-config.sh"
)
OBSERVABILITY_DOCUMENT_PATH = ROOT / "docs" / "deployment" / "cloudwatch-staging-observability.md"
DEPLOYMENT_DOCUMENT_PATH = ROOT / "docs" / "deployment" / "docker-ecr-ssm-cd.md"
VALKEY_MEMORY_METRICS_SCRIPT_PATH = (
    ROOT / "deploy" / "monitoring" / "publish-valkey-memory-metrics.sh"
)
VALKEY_MEMORY_SERVICE_PATH = (
    ROOT / "deploy" / "monitoring" / "miriyum-valkey-memory-metrics.service"
)
VALKEY_MEMORY_TIMER_PATH = (
    ROOT / "deploy" / "monitoring" / "miriyum-valkey-memory-metrics.timer"
)
RISK_EVENT_DELIVERY_PATH = (
    ROOT
    / "backend"
    / "src"
    / "main"
    / "java"
    / "com"
    / "miriyum"
    / "domain"
    / "auth"
    / "riskevent"
    / "RefreshTokenRiskEventDelivery.java"
)
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
        cls.nginx_selector = NGINX_SELECTOR_PATH.read_text(encoding="utf-8")
        cls.observability_document = OBSERVABILITY_DOCUMENT_PATH.read_text(encoding="utf-8")
        cls.deployment_document = DEPLOYMENT_DOCUMENT_PATH.read_text(encoding="utf-8")
        cls.valkey_memory_metrics_script = VALKEY_MEMORY_METRICS_SCRIPT_PATH.read_text(
            encoding="utf-8"
        )
        cls.valkey_memory_service = VALKEY_MEMORY_SERVICE_PATH.read_text(encoding="utf-8")
        cls.valkey_memory_timer = VALKEY_MEMORY_TIMER_PATH.read_text(encoding="utf-8")
        cls.risk_event_delivery = RISK_EVENT_DELIVERY_PATH.read_text(encoding="utf-8")
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

    @staticmethod
    def duration_seconds(value):
        match = re.fullmatch(r"(\d+)(ms|s|m|h)", value)
        if match is None:
            raise ValueError(f"Unsupported Compose duration: {value}")

        amount = int(match.group(1))
        unit = match.group(2)
        return {
            "ms": amount / 1000,
            "s": amount,
            "m": amount * 60,
            "h": amount * 60 * 60,
        }[unit]

    def assert_health_wait_covers_compose_budget(self, service, timeout_variable):
        healthcheck = self.compose_config["services"][service]["healthcheck"]
        healthcheck_budget = (
            self.duration_seconds(healthcheck["start_period"])
            + healthcheck["retries"]
            * (
                self.duration_seconds(healthcheck["interval"])
                + self.duration_seconds(healthcheck["timeout"])
            )
        )
        timeout_match = re.search(
            rf'{timeout_variable}="\$\{{{timeout_variable}:-(\d+)\}}"',
            self.deploy_script,
        )

        self.assertIsNotNone(timeout_match)
        self.assertGreaterEqual(int(timeout_match.group(1)), healthcheck_budget)

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

    def test_mysql_allows_trigger_migrations_when_binary_logging_is_enabled(self):
        mysql_command = self.compose_config["services"]["mysql"]["command"]

        self.assertIn("--log-bin-trust-function-creators=1", mysql_command)

    def test_deployment_runbook_keeps_trigger_migration_recovery_manual_and_verifiable(self):
        for expected_text in (
            "log_bin_trust_function_creators",
            "flyway_schema_history",
            "SHOW TRIGGERS",
            "Never automatically delete objects or modify Flyway history.",
        ):
            self.assertIn(expected_text, self.deployment_document)

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

    def test_pending_risk_event_count_is_observable_without_identifier_dimensions(self):
        self.assertIn(
            "miriyum-staging-refresh-risk-event-pending-count", self.resource_script
        )
        self.assertIn("RefreshTokenRiskEventPendingCount", self.resource_script)
        self.assertIn(
            "[..., marker = refresh_token_risk_event_pending_count, label = pending_count, pending_count]",
            self.resource_script,
        )
        self.assertIn("metricValue=$pending_count", self.resource_script)
        self.assertIn(
            '"refresh_token_risk_event_pending_count pending_count {}"',
            self.risk_event_delivery,
        )
        self.assertIn("MiriYum pending refresh risk event index members", self.resource_script)

    def test_refresh_risk_marker_integrity_failures_become_cloudwatch_metrics(self):
        expected_events = (
            "refresh_token_risk_event_marker_malformed",
            "refresh_token_risk_event_marker_quarantine_failed",
            "refresh_token_risk_event_stale_index_cleanup_failed",
        )
        expected_metrics = (
            "RefreshTokenRiskEventMarkerMalformed",
            "RefreshTokenRiskEventMarkerQuarantineFailed",
            "RefreshTokenRiskEventStaleIndexCleanupFailed",
        )

        for event in expected_events:
            self.assertIn(event, self.resource_script)
        for metric in expected_metrics:
            self.assertIn(metric, self.resource_script)
        self.assertIn("MiriYum refresh risk marker integrity failures", self.resource_script)

    def test_refresh_token_absolute_lifetime_cap_is_observable(self):
        self.assertIn(
            "miriyum-staging-refresh-token-absolute-lifetime-cap-applied",
            self.resource_script,
        )
        self.assertIn("RefreshTokenAbsoluteLifetimeCapApplied", self.resource_script)
        self.assertIn("refresh_token_absolute_lifetime_cap_applied", self.resource_script)

    def test_resource_script_emits_pending_count_and_long_stay_metric_filters(self):
        with tempfile.TemporaryDirectory() as directory:
            temporary_path = Path(directory)
            bin_path = temporary_path / "bin"
            bin_path.mkdir()
            arguments_path = temporary_path / "aws-arguments"
            aws_path = bin_path / "aws"
            aws_path.write_text(
                """#!/usr/bin/env bash
printf '%s\\n' \"$@\" >> \"$AWS_TEST_ARGUMENTS\"
case \"$1 $2\" in
  \"logs describe-log-groups\"|\"sns list-subscriptions-by-topic\") echo 0 ;;
  \"sns create-topic\") echo arn:aws:sns:ap-northeast-2:123456789012:miriyum-staging-alerts ;;
esac
""",
                encoding="utf-8",
            )
            aws_path.chmod(0o755)
            environment = os.environ.copy()
            environment.update(
                {
                    "PATH": f"{bin_path}{os.pathsep}{environment['PATH']}",
                    "AWS_REGION": "ap-northeast-2",
                    "EC2_INSTANCE_ID": "i-1234567890abcdef0",
                    "ALARM_EMAIL": "test@example.com",
                    "AWS_TEST_ARGUMENTS": str(arguments_path),
                }
            )

            result = subprocess.run(
                [BASH_EXECUTABLE, str(RESOURCE_SCRIPT_PATH)],
                cwd=ROOT,
                capture_output=True,
                env=environment,
                text=True,
                encoding="utf-8",
                errors="replace",
            )

            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            captured_arguments = arguments_path.read_text(encoding="utf-8")
            self.assertIn(
                "[..., marker = refresh_token_risk_event_pending_count, label = pending_count, pending_count]",
                captured_arguments,
            )
            self.assertIn("metricValue=$pending_count", captured_arguments)
            self.assertIn(
                "miriyum-staging-refresh-risk-event-marker-long-stay\n"
                "--filter-pattern\n"
                '"event=refresh_token_risk_event_marker_long_stay"\n'
                "--metric-transformations\n"
                "metricName=RefreshTokenRiskEventMarkerLongStay,"
                "metricNamespace=MiriYum/Staging,metricValue=1,defaultValue=0",
                captured_arguments,
            )
            self.assertNotIn("metricValue=$long_stay_count", captured_arguments)

    def test_staging_can_disable_reservation_hold_expiration_through_env_file(self):
        staging_environment = ENV_EXAMPLE_PATH.read_text(encoding="utf-8").replace(
            "MIRIYUM_RESERVATION_HOLD_EXPIRATION_ENABLED=true",
            "MIRIYUM_RESERVATION_HOLD_EXPIRATION_ENABLED=false",
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
            "false",
            backend_environment.get(
                "MIRIYUM_RESERVATION_HOLD_EXPIRATION_ENABLED"
            ),
        )
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
        self.assertIn("wait_for_valkey_health()", self.deploy_script)
        self.assertIn('ps -q valkey', self.deploy_script)
        self.assertIn('"${health}" == "healthy"', self.deploy_script)
        self.assertIn("while :; do", self.deploy_script)
        self.assertIn("sleep 2", self.deploy_script)

    def test_service_health_waits_cover_their_compose_healthcheck_budgets(self):
        self.assert_health_wait_covers_compose_budget(
            "mysql", "MYSQL_HEALTH_TIMEOUT_SECONDS"
        )
        self.assert_health_wait_covers_compose_budget(
            "valkey", "VALKEY_HEALTH_TIMEOUT_SECONDS"
        )

    def test_deployment_fails_when_valkey_health_wait_times_out(self):
        self.assertIn("verify_valkey()", self.deploy_script)
        self.assertIn("Valkey health check timed out after", self.deploy_script)
        self.assertIn("last status:", self.deploy_script)
        self.assertIn("publish_deployment_health 0", self.deploy_script)
        self.assertIn("Unauthenticated Valkey ping did not return NOAUTH.", self.deploy_script)
        self.assertIn('grep -qx PONG', self.deploy_script)
        self.assertIn('port valkey 6379', self.deploy_script)
        self.assertIn('compose_command --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" logs --tail 100 valkey', self.deploy_script)

    def test_nginx_selector_requires_certificate_and_private_key_for_tls(self):
        self.assertIn('private_key="/etc/letsencrypt/live/${STAGING_DOMAIN}/privkey.pem"', self.nginx_selector)
        self.assertIn(
            'elif [ -r "${certificate}" ] && [ -r "${private_key}" ]; then',
            self.nginx_selector,
        )

    def test_deployment_fails_and_recovers_http_when_nginx_tls_validation_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            temporary_path = Path(directory)
            metric_path = temporary_path / "metric-arguments"
            compose_path = temporary_path / "compose-arguments"
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
    printf '%s\\n' "$@" > "$NGINX_TEST_METRIC"
  fi
  return 0
}
docker() {
  if [[ "$1" == "login" ]]; then
    cat >/dev/null
    return 0
  fi
  if [[ "$1" == "compose" ]]; then
    if [[ "$*" == *"ps --status running --services nginx"* ]]; then
      echo nginx
      return 0
    fi
    if [[ "$*" == *"exec -T nginx nginx -t"* ]]; then
      printf '%s\\n' "$*" >> "$NGINX_TEST_COMPOSE"
      return 1
    fi
    printf '%s\\n' "$*" >> "$NGINX_TEST_COMPOSE"
  fi
  return 0
}
curl() { return 0; }
wait_for_mysql_health() { return 0; }
verify_valkey() { return 0; }
backfill_pending_risk_event_index() { return 0; }
backfill_risk_event_occurrence_counters() { return 0; }
main
""",
                {
                    "AWS_REGION": "ap-northeast-2",
                    "BACKEND_IMAGE": "example.invalid/backend:sha",
                    "ENV_FILE": self.to_bash_path(environment_file),
                    "COMPOSE_FILE": self.to_bash_path(temporary_path / "docker-compose.yml"),
                    "NGINX_TEST_METRIC": self.to_bash_path(metric_path),
                    "NGINX_TEST_COMPOSE": self.to_bash_path(compose_path),
                },
            )

            commands = compose_path.read_text(encoding="utf-8")
            self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn("Value=0", metric_path.read_text(encoding="utf-8"))
            self.assertIn("exec -T nginx nginx -t", commands)
            self.assertIn("up -d --force-recreate nginx", commands)
            self.assertIn("logs --tail 100 nginx", commands)

    def test_deployment_stops_before_registry_login_when_runtime_environment_is_invalid(self):
        with tempfile.TemporaryDirectory() as directory:
            temporary_path = Path(directory)
            environment_file = temporary_path / ".env"
            environment_file.write_text("placeholder=true\n", encoding="utf-8")
            command_log = temporary_path / "commands"
            result = self.run_deploy_script(
                """
aws() {
  echo "aws $*" >> "$PREFLIGHT_COMMAND_LOG"
  return 1
}
docker() {
  echo "docker $*" >> "$PREFLIGHT_COMMAND_LOG"
  if [[ "$*" == *"config --quiet"* ]]; then
    echo "MIRIYUM_NOTIFICATION_HISTORY_CURSOR_SECRET is required" >&2
    return 1
  fi
  return 1
}
main
""",
                {
                    "AWS_REGION": "ap-northeast-2",
                    "BACKEND_IMAGE": "example.invalid/backend:sha",
                    "ENV_FILE": self.to_bash_path(environment_file),
                    "COMPOSE_FILE": self.to_bash_path(temporary_path / "docker-compose.yml"),
                    "PREFLIGHT_COMMAND_LOG": self.to_bash_path(command_log),
                },
            )

            self.assertNotEqual(0, result.returncode)
            self.assertIn("MIRIYUM_NOTIFICATION_HISTORY_CURSOR_SECRET is required", result.stderr)
            self.assertEqual(
                [
                    "docker compose --env-file "
                    f"{self.to_bash_path(environment_file)} -f "
                    f"{self.to_bash_path(temporary_path / 'docker-compose.yml')} config --quiet"
                ],
                command_log.read_text(encoding="utf-8").splitlines(),
            )

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

    def test_deployment_fails_when_risk_event_backfill_fails_before_set_only_delivery_starts(self):
        with tempfile.TemporaryDirectory() as directory:
            temporary_path = Path(directory)
            metric_path = temporary_path / "metric-arguments"
            log_path = temporary_path / "compose-arguments"
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
    printf '%s\\n' "$@" > "$BACKFILL_TEST_METRIC"
  fi
  return 0
}
docker() {

  if [[ "$1" == "login" ]]; then
    cat >/dev/null
    return 0
  fi
  if [[ "$1" == "compose" ]]; then
    if [[ "$*" == *"ps -q mysql"* ]]; then
      echo mysql-container
      return 0
    fi
    if [[ "$*" == *"sh -ec"* ]]; then
      return 1
    fi
    printf '%s\\n' "$*" >> "$BACKFILL_TEST_COMPOSE"
  fi
  if [[ "$1" == "inspect" ]]; then
    echo healthy
    return 0
  fi
  return 0
}
curl() { return 0; }
wait_for_valkey_health() { return 0; }
verify_valkey() { return 0; }
main
""",
                {
                    "AWS_REGION": "ap-northeast-2",
                    "BACKEND_IMAGE": "example.invalid/backend:sha",
                    "ENV_FILE": self.to_bash_path(environment_file),
                    "COMPOSE_FILE": self.to_bash_path(temporary_path / "docker-compose.yml"),
                    "BACKFILL_TEST_METRIC": self.to_bash_path(metric_path),
                    "BACKFILL_TEST_COMPOSE": self.to_bash_path(log_path),
                },
            )

            self.assertEqual(1, result.returncode, result.stdout + result.stderr)
            self.assertIn("Risk event state backfill failed; aborting deployment", result.stderr)
            self.assertIn("Value=0", metric_path.read_text(encoding="utf-8"))
            self.assertIn("logs --tail 100 valkey", log_path.read_text(encoding="utf-8"))

    def test_deployment_backfills_pending_index_before_starting_the_new_backend(self):
        main_body = self.deploy_script[self.deploy_script.index("\nmain() {") :]
        backend_stop = main_body.index('stop backend')
        valkey_start = main_body.index('up -d mysql valkey')
        mysql_wait = main_body.index('wait_for_mysql_health')
        pending_index_backfill = main_body.index('backfill_pending_risk_event_index')
        occurrence_counter_backfill = main_body.index(
            'backfill_risk_event_occurrence_counters'
        )
        backend_start = main_body.index('up -d --remove-orphans')

        self.assertLess(backend_stop, valkey_start)
        self.assertLess(valkey_start, pending_index_backfill)
        self.assertLess(valkey_start, mysql_wait)
        self.assertLess(mysql_wait, pending_index_backfill)
        self.assertLess(pending_index_backfill, occurrence_counter_backfill)
        self.assertLess(occurrence_counter_backfill, backend_start)

    def test_deployment_backfills_db_occurrence_count_to_family_bound_counter(self):
        with tempfile.TemporaryDirectory() as directory:
            temporary_path = Path(directory)
            valkey_arguments_path = self.to_bash_path(
                temporary_path / "valkey-arguments"
            )
            event_key = (
                "auth:risk:pending:consumer:family-123:"
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
            )
            result = self.run_deploy_script(
                f"""
docker() {{
  if [[ "$*" == *" mysql "* ]]; then
    printf '%s\\t2\\n' '{event_key}'
    return 0
  fi
  if [[ "$*" == *" valkey "* ]]; then
    printf '%s\\n' "$*" >> "$RISK_OCCURRENCE_VALKEY_ARGUMENTS"
    return 0
  fi
  return 1
}}
backfill_risk_event_occurrence_counters
""",
                {"RISK_OCCURRENCE_VALKEY_ARGUMENTS": valkey_arguments_path},
            )

            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            valkey_arguments = Path(directory, "valkey-arguments").read_text(
                encoding="utf-8"
            )
            self.assertIn("auth:risk:occurrence:consumer:family-123:", valkey_arguments)
            self.assertIn("auth:refresh:consumer:family-123", valkey_arguments)
            self.assertIn(event_key, valkey_arguments)
            self.assertIn(" 3", valkey_arguments)
            self.assertIn("EXPIRETIME", valkey_arguments)
            self.assertIn("EXPIREAT", valkey_arguments)
            self.assertIn("HGET", valkey_arguments)

    def test_deployment_preserves_lua_integer_pattern_through_container_shell(self):
        with tempfile.TemporaryDirectory() as directory:
            temporary_path = Path(directory)
            bin_path = temporary_path / "bin"
            bin_path.mkdir()
            valkey_cli = bin_path / "valkey-cli"
            event_key = (
                "auth:risk:pending:consumer:family-123:"
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
            )
            valkey_cli.write_text(
                """#!/usr/bin/env bash
if [[ "$1" == "--raw" ]]; then
  shift
fi

if [[ "$1" != "EVAL" ]]; then
  exit 1
fi

if [[ "$2" != *'string.match(value, "^[1-9][0-9]*$")'* ]]; then
  echo "Lua integer pattern was not preserved" >&2
  exit 1
fi
""",
                encoding="utf-8",
            )
            valkey_cli.chmod(0o755)

            result = self.run_deploy_script(
                f"""
PATH="$TEST_VALKEY_BIN:$PATH"
export PATH

docker() {{
  local arguments=("$@")
  local index

  if [[ "$*" == *" mysql "* ]]; then
    printf '%s\\t2\\n' '{event_key}'
    return 0
  fi

  for ((index = 0; index < ${{#arguments[@]}} - 2; index++)); do
    if [[ "${{arguments[index]}}" == "sh" && "${{arguments[index + 1]}}" == "-ec" ]]; then
      bash -ec "${{arguments[index + 2]}}" "${{arguments[@]:index + 3}}"
      return
    fi
  done
  return 1
}}

backfill_risk_event_occurrence_counters
""",
                {"TEST_VALKEY_BIN": self.to_bash_path(bin_path)},
            )

            self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_deployment_backfill_propagates_valkey_scan_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            temporary_path = Path(directory)
            bin_path = temporary_path / "bin"
            bin_path.mkdir()
            valkey_cli = bin_path / "valkey-cli"
            valkey_cli.write_text(
                """#!/usr/bin/env bash
if [[ "$1" == "--raw" ]]; then
  shift
fi

if [[ "$1" == "SCAN" ]]; then
  echo "simulated SCAN failure" >&2
  exit 1
fi

exit 1
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

if backfill_pending_risk_event_index; then
  exit 0
fi
exit 1
""",
                {"TEST_VALKEY_BIN": self.to_bash_path(bin_path)},
            )

            self.assertNotEqual(0, result.returncode)
            self.assertIn("simulated SCAN failure", result.stderr)

    def test_deployment_backfill_rejects_an_invalid_scan_cursor(self):
        with tempfile.TemporaryDirectory() as directory:
            temporary_path = Path(directory)
            bin_path = temporary_path / "bin"
            bin_path.mkdir()
            valkey_cli = bin_path / "valkey-cli"
            valkey_cli.write_text(
                """#!/usr/bin/env bash
if [[ "$1" == "--raw" ]]; then
  shift
fi

if [[ "$1" == "SCAN" ]]; then
  printf 'invalid-cursor\\nauth:risk:pending:marker\\n'
  exit 0
fi

exit 1
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

if backfill_pending_risk_event_index; then
  exit 0
fi
exit 1
""",
                {"TEST_VALKEY_BIN": self.to_bash_path(bin_path)},
            )

            self.assertNotEqual(0, result.returncode)
            self.assertIn("Invalid SCAN cursor", result.stderr)

    def test_deployment_backfill_stops_after_the_configured_scan_page_limit(self):
        with tempfile.TemporaryDirectory() as directory:
            temporary_path = Path(directory)
            bin_path = temporary_path / "bin"
            bin_path.mkdir()
            valkey_cli = bin_path / "valkey-cli"
            valkey_cli.write_text(
                """#!/usr/bin/env bash
if [[ "$1" == "--raw" ]]; then
  shift
fi

if [[ "$1" == "SCAN" ]]; then
  printf '1\\n'
  exit 0
fi

exit 1
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

if backfill_pending_risk_event_index; then
  exit 0
fi
exit 1
""",
                {
                    "RISK_EVENT_BACKFILL_MAX_SCAN_PAGES": "2",
                    "TEST_VALKEY_BIN": self.to_bash_path(bin_path),
                },
            )

            self.assertNotEqual(0, result.returncode)
            self.assertIn("Risk event index backfill exceeded 2 SCAN pages", result.stderr)

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

    def test_mysql_health_wait_accepts_starting_then_healthy(self):
        with tempfile.TemporaryDirectory() as directory:
            temporary_path = Path(directory)
            state_path = temporary_path / "inspect-count"
            result = self.run_deploy_script(
                """
docker() {
  if [[ "$1" == "compose" ]]; then
    echo mysql-container
    return 0
  fi
  if [[ "$1" == "inspect" ]]; then
    local count=0
    [[ -f "$MYSQL_TEST_STATE" ]] && count=$(cat "$MYSQL_TEST_STATE")
    count=$((count + 1))
    echo "$count" > "$MYSQL_TEST_STATE"
    [[ "$count" -eq 1 ]] && echo starting || echo healthy
    return 0
  fi
  return 1
}
sleep() { :; }
wait_for_mysql_health
""",
                {
                    "MYSQL_HEALTH_TIMEOUT_SECONDS": "5",
                    "MYSQL_TEST_STATE": self.to_bash_path(state_path),
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

    def test_main_publishes_failed_health_when_mysql_never_becomes_healthy(self):
        with tempfile.TemporaryDirectory() as directory:
            temporary_path = Path(directory)
            metric_path = temporary_path / "metric-arguments"
            compose_path = temporary_path / "compose-arguments"
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
    printf '%s\\n' "$@" > "$MYSQL_TEST_METRIC"
  fi
  return 0
}
docker() {
  if [[ "$1" == "login" ]]; then
    cat >/dev/null
    return 0
  fi
  if [[ "$1" == "compose" && "$*" == *"logs --tail 100 mysql"* ]]; then
    printf '%s\\n' "$*" > "$MYSQL_TEST_COMPOSE"
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
                    "MYSQL_HEALTH_TIMEOUT_SECONDS": "0",
                    "MYSQL_TEST_METRIC": self.to_bash_path(metric_path),
                    "MYSQL_TEST_COMPOSE": self.to_bash_path(compose_path),
                },
            )

            self.assertNotEqual(0, result.returncode)
            self.assertIn("Value=0", metric_path.read_text(encoding="utf-8"))
            self.assertIn(
                "logs --tail 100 mysql", compose_path.read_text(encoding="utf-8")
            )

    @staticmethod
    def run_deploy_script(script, extra_environment):
        environment = os.environ.copy()
        environment.update(extra_environment)
        return subprocess.run(
            [
                BASH_EXECUTABLE,
                "-c",
                f"source <(tr -d '\\r' < deploy/deploy.sh); load_runtime_config() {{ :; }}; {script}",
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

    def test_refresh_risk_marker_long_stay_log_becomes_a_cloudwatch_metric_and_alarm(self):
        self.assertIn(
            'refresh_token_risk_event_marker_long_stay',
            self.resource_script,
        )
        self.assertIn(
            'metricName=RefreshTokenRiskEventMarkerLongStay',
            self.resource_script,
        )
        start = self.resource_script.index(
            'put_alarm "miriyum-staging-refresh-risk-event-marker-long-stay"'
        )
        alarm = self.resource_script[start:]
        self.assertIn("--metric-name RefreshTokenRiskEventMarkerLongStay", alarm)
        self.assertIn("--statistic Sum", alarm)
        self.assertIn("--threshold 0", alarm)
        self.assertIn("--comparison-operator GreaterThanThreshold", alarm)

    def test_auth_valkey_memory_timer_is_host_scoped_and_runs_every_minute(self):
        self.assertIn("EnvironmentFile=/opt/miriyum/.env", self.valkey_memory_service)
        self.assertIn(
            "ExecStart=/opt/miriyum/monitoring/publish-valkey-memory-metrics.sh",
            self.valkey_memory_service,
        )
        self.assertIn("OnUnitActiveSec=60s", self.valkey_memory_timer)
        self.assertIn("Unit=miriyum-valkey-memory-metrics.service", self.valkey_memory_timer)
        self.assertIn(
            "systemctl enable --now miriyum-valkey-memory-metrics.timer",
            self.workflow,
        )
        self.assertIn(
            "systemd-analyze verify /etc/systemd/system/miriyum-valkey-memory-metrics.service /etc/systemd/system/miriyum-valkey-memory-metrics.timer",
            self.workflow,
        )

    def test_auth_valkey_memory_success_publishes_bytes_and_utilization(self):
        with tempfile.TemporaryDirectory() as directory:
            temporary_path = Path(directory)
            aws_arguments = temporary_path / "aws-arguments"
            fake_aws = temporary_path / "aws"
            fake_curl = temporary_path / "curl"
            fake_docker = temporary_path / "docker"
            fake_aws.write_text(
                '#!/usr/bin/env bash\nprintf "%s\\n" "$@" > "$TEST_AWS_ARGUMENTS"\n',
                encoding="utf-8",
            )
            fake_curl.write_text(
                '#!/usr/bin/env bash\nif [[ " $* " == *" --request PUT "* ]]; then echo token; else echo i-test; fi\n',
                encoding="utf-8",
            )
            fake_docker.write_text(
                '#!/usr/bin/env bash\nprintf "used_memory:1048576\\r\\nmaxmemory:8388608\\r\\n"\n',
                encoding="utf-8",
            )
            for executable in (fake_aws, fake_curl, fake_docker):
                executable.chmod(0o755)

            result = subprocess.run(
                [BASH_EXECUTABLE, str(VALKEY_MEMORY_METRICS_SCRIPT_PATH)],
                cwd=ROOT,
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                env={
                    **os.environ,
                    "AWS_BIN": str(fake_aws),
                    "CURL_BIN": str(fake_curl),
                    "DOCKER_BIN": str(fake_docker),
                    "AWS_REGION": "ap-northeast-2",
                    "MIRIYUM_VALKEY_PASSWORD": "test-only-password",
                    "TEST_AWS_ARGUMENTS": str(aws_arguments),
                },
            )

            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn("event=auth_valkey_memory_collected", result.stdout)
            published = aws_arguments.read_text(encoding="utf-8")
            self.assertIn("MetricName=AuthValkeyUsedMemoryBytes,Value=1048576", published)
            self.assertIn("MetricName=AuthValkeyMaxMemoryBytes,Value=8388608", published)
            self.assertIn(
                "MetricName=AuthValkeyMemoryUtilizationPercent,Value=12.50", published
            )
            self.assertIn("MetricName=AuthValkeyMemoryCollectionHeartbeat,Value=1", published)
            self.assertNotIn("test-only-password", result.stdout + result.stderr + published)

    def test_auth_valkey_memory_invalid_maxmemory_publishes_failure_not_zero_percent(self):
        with tempfile.TemporaryDirectory() as directory:
            temporary_path = Path(directory)
            aws_arguments = temporary_path / "aws-arguments"
            fake_aws = temporary_path / "aws"
            fake_curl = temporary_path / "curl"
            fake_docker = temporary_path / "docker"
            fake_aws.write_text(
                '#!/usr/bin/env bash\nprintf "%s\\n" "$@" > "$TEST_AWS_ARGUMENTS"\n',
                encoding="utf-8",
            )
            fake_curl.write_text(
                '#!/usr/bin/env bash\nif [[ " $* " == *" --request PUT "* ]]; then echo token; else echo i-test; fi\n',
                encoding="utf-8",
            )
            fake_docker.write_text(
                '#!/usr/bin/env bash\nprintf "used_memory:1048576\\nmaxmemory:0\\n"\n',
                encoding="utf-8",
            )
            for executable in (fake_aws, fake_curl, fake_docker):
                executable.chmod(0o755)

            result = subprocess.run(
                [BASH_EXECUTABLE, str(VALKEY_MEMORY_METRICS_SCRIPT_PATH)],
                cwd=ROOT,
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                env={
                    **os.environ,
                    "AWS_BIN": str(fake_aws),
                    "CURL_BIN": str(fake_curl),
                    "DOCKER_BIN": str(fake_docker),
                    "AWS_REGION": "ap-northeast-2",
                    "MIRIYUM_VALKEY_PASSWORD": "test-only-password",
                    "TEST_AWS_ARGUMENTS": str(aws_arguments),
                },
            )

            self.assertNotEqual(0, result.returncode)
            self.assertIn("event=auth_valkey_memory_collection_failed", result.stderr)
            published = aws_arguments.read_text(encoding="utf-8")
            self.assertIn("MetricName=AuthValkeyMemoryCollectionFailure,Value=1", published)
            self.assertNotIn("AuthValkeyMemoryUtilizationPercent", published)
            self.assertNotIn("AuthValkeyMemoryCollectionHeartbeat", published)

    def test_auth_valkey_memory_imds_failure_leaves_heartbeat_absent_for_dead_man_alarm(self):
        with tempfile.TemporaryDirectory() as directory:
            temporary_path = Path(directory)
            aws_arguments = temporary_path / "aws-arguments"
            fake_aws = temporary_path / "aws"
            fake_curl = temporary_path / "curl"
            fake_aws.write_text(
                '#!/usr/bin/env bash\nprintf "%s\\n" "$@" > "$TEST_AWS_ARGUMENTS"\n',
                encoding="utf-8",
            )
            fake_curl.write_text('#!/usr/bin/env bash\nexit 1\n', encoding="utf-8")
            for executable in (fake_aws, fake_curl):
                executable.chmod(0o755)

            result = subprocess.run(
                [BASH_EXECUTABLE, str(VALKEY_MEMORY_METRICS_SCRIPT_PATH)],
                cwd=ROOT,
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                env={
                    **os.environ,
                    "AWS_BIN": str(fake_aws),
                    "CURL_BIN": str(fake_curl),
                    "AWS_REGION": "ap-northeast-2",
                    "MIRIYUM_VALKEY_PASSWORD": "test-only-password",
                    "TEST_AWS_ARGUMENTS": str(aws_arguments),
                },
            )

            self.assertNotEqual(0, result.returncode)
            self.assertFalse(aws_arguments.exists())

    def test_auth_valkey_memory_collection_failure_alarm_and_dashboard_are_configured(self):
        start = self.resource_script.index(
            'put_alarm "miriyum-staging-auth-valkey-memory-collection-failed"'
        )
        alarm = self.resource_script[start:]
        self.assertIn("--metric-name AuthValkeyMemoryCollectionFailure", alarm)
        self.assertIn("--threshold 0", alarm)
        missing_start = self.resource_script.index(
            'put_missing_data_alarm "miriyum-staging-auth-valkey-memory-collection-missing"'
        )
        missing_alarm = self.resource_script[missing_start:]
        self.assertIn("--metric-name AuthValkeyMemoryCollectionHeartbeat", missing_alarm)
        self.assertIn("--threshold 0.5", missing_alarm)
        self.assertIn("--comparison-operator LessThanThreshold", missing_alarm)
        self.assertIn("--treat-missing-data breaching", self.resource_script)
        self.assertIn("AuthValkeyUsedMemoryBytes", self.resource_script)
        self.assertIn("AuthValkeyMaxMemoryBytes", self.resource_script)
        self.assertIn("AuthValkeyMemoryUtilizationPercent", self.resource_script)
        self.assertIn("AuthValkeyMemoryCollectionHeartbeat", self.resource_script)

class ReservationHoldReconciliationAlarmTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.resource_script = RESOURCE_SCRIPT_PATH.read_text(encoding="utf-8")
        cls.observability_document = OBSERVABILITY_DOCUMENT_PATH.read_text(
            encoding="utf-8"
        )

    def test_reservation_hold_reconciliation_stall_log_becomes_a_cloudwatch_metric(self):
        self.assertIn("aws logs put-metric-filter", self.resource_script)
        self.assertIn(
            'event=reservation_hold_reconciliation_stalled',
            self.resource_script,
        )
        self.assertIn(
            'metricName=ReservationHoldReconciliationStalled',
            self.resource_script,
        )

    def test_reservation_hold_reconciliation_stall_metric_has_a_level_triggered_alarm(self):
        start = self.resource_script.index(
            'put_alarm "miriyum-staging-reservation-hold-reconciliation-stalled"'
        )
        alarm = self.resource_script[start:]
        self.assertIn("--metric-name ReservationHoldReconciliationStalled", alarm)
        self.assertIn("--statistic Sum", alarm)
        self.assertIn("--period 300", alarm)
        self.assertIn("--threshold 0", alarm)
        self.assertIn("--comparison-operator GreaterThanThreshold", alarm)
        self.assertIn("--treat-missing-data notBreaching", self.resource_script)

    def test_observability_document_describes_the_ten_minute_level_signal(self):
        self.assertIn("`occurredAt`부터 10분", self.observability_document)
        self.assertIn(
            "event=reservation_hold_reconciliation_stalled long_stay_count=3",
            self.observability_document,
        )
        self.assertIn("`ReservationHoldReconciliationStalled=1`", self.observability_document)
        self.assertIn("`notBreaching`으로 복귀", self.observability_document)


if __name__ == "__main__":
    unittest.main()
