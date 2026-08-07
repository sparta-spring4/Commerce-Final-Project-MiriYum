import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CONFIG_PATH = ROOT / "deploy" / "monitoring" / "cloudwatch-agent-config.json"
RESOURCE_SCRIPT_PATH = ROOT / "deploy" / "monitoring" / "create-cloudwatch-resources.sh"
WORKFLOW_PATH = ROOT / ".github" / "workflows" / "backend-cd.yml"
COMPOSE_PATH = ROOT / "deploy" / "docker-compose.prod.yml"


class CloudWatchObservabilityConfigTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.config = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
        cls.resource_script = RESOURCE_SCRIPT_PATH.read_text(encoding="utf-8")
        cls.workflow = WORKFLOW_PATH.read_text(encoding="utf-8")
        cls.compose = COMPOSE_PATH.read_text(encoding="utf-8")

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

    def test_dashboard_includes_ec2_network_metrics(self):
        self.assertIn('["AWS/EC2", "NetworkIn", "InstanceId", "$EC2_INSTANCE_ID"]', self.resource_script)
        self.assertIn('["AWS/EC2", "NetworkOut", "InstanceId", "$EC2_INSTANCE_ID"]', self.resource_script)


if __name__ == "__main__":
    unittest.main()
