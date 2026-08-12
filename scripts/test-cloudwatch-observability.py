import json
import os
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
            text=True,
        )
        return json.loads(result.stdout)

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

        environment = os.environ.copy()
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

    def test_deployment_fails_when_valkey_runtime_verification_fails(self):
        self.assertIn("verify_valkey()", self.deploy_script)
        self.assertIn('ps -q valkey', self.deploy_script)
        self.assertIn("Valkey health check is", self.deploy_script)
        self.assertIn("Unauthenticated Valkey ping did not return NOAUTH.", self.deploy_script)
        self.assertIn('grep -qx PONG', self.deploy_script)
        self.assertIn('port valkey 6379', self.deploy_script)
        self.assertIn('docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" logs --tail 100 valkey', self.deploy_script)

    def test_dashboard_includes_ec2_network_metrics(self):
        self.assertIn('["AWS/EC2", "NetworkIn", "InstanceId", "$EC2_INSTANCE_ID"]', self.resource_script)
        self.assertIn('["AWS/EC2", "NetworkOut", "InstanceId", "$EC2_INSTANCE_ID"]', self.resource_script)


if __name__ == "__main__":
    unittest.main()
