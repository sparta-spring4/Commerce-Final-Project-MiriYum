#!/usr/bin/env python3
"""Guards the approved production compute OFF/ON boundary."""

from pathlib import Path
import re
import unittest


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
DOWN_SCRIPT = REPOSITORY_ROOT / "infra/terraform/production/scripts/production-down.ps1"
UP_SCRIPT = REPOSITORY_ROOT / "infra/terraform/production/scripts/production-up.ps1"
AUTOSCALING_CONFIGURATION = REPOSITORY_ROOT / "infra/terraform/production/autoscaling.tf"
TERRAFORM_VERSIONS = REPOSITORY_ROOT / "infra/terraform/production/versions.tf"


class ProductionTerraformLifecycleContractTest(unittest.TestCase):
    def test_scripts_limit_changes_to_compute_and_rds_state(self) -> None:
        source = DOWN_SCRIPT.read_text(encoding="utf-8") + UP_SCRIPT.read_text(encoding="utf-8")

        self.assertIn('$ExpectedAccountId = "579750808837"', source)
        self.assertIn('$Region = "ap-northeast-2"', source)
        self.assertIn('$Cluster = "miriyum-prod-cluster"', source)
        self.assertIn('$Service = "miriyum-prod-backend-service"', source)
        self.assertIn('$TaskFamily = "miriyum-production-backend"', source)
        self.assertIn("--desired-count 0", source)
        self.assertIn("--desired-count 2", source)
        self.assertIn("application-autoscaling register-scalable-target", source)
        self.assertIn("DynamicScalingInSuspended=true", source)
        self.assertIn("DynamicScalingOutSuspended=true", source)
        self.assertIn("DynamicScalingInSuspended=false", source)
        self.assertIn("DynamicScalingOutSuspended=false", source)
        self.assertIn("stop-db-instance", source)
        self.assertIn("start-db-instance", source)
        self.assertIn("Wait-ForRdsStatus", source)
        self.assertIn("$TimeoutSeconds = 900", source)

    def test_scripts_do_not_take_terraform_or_persistent_resource_ownership(self) -> None:
        source = DOWN_SCRIPT.read_text(encoding="utf-8") + UP_SCRIPT.read_text(encoding="utf-8")

        self.assertNotIn("terraform ", source.lower())
        self.assertNotIn("delete-", source.lower())
        self.assertNotIn("blue/green", source.lower())

    def test_up_preflight_runs_before_any_rds_or_ecs_mutation(self) -> None:
        source = UP_SCRIPT.read_text(encoding="utf-8")

        self.assertLess(
            source.index("Assert-BackendServiceFamily\n$rdsStatus"),
            source.index("start-db-instance"),
        )
        self.assertLess(
            source.index("Assert-BackendServiceFamily\n$rdsStatus"),
            source.index("update-service"),
        )

    def test_scripts_converge_rds_transitional_states_before_mutation(self) -> None:
        up_source = UP_SCRIPT.read_text(encoding="utf-8")
        down_source = DOWN_SCRIPT.read_text(encoding="utf-8")

        self.assertIn('$rdsStatus -eq "stopping"', up_source)
        self.assertIn('Wait-ForRdsStatus "stopped"', up_source)
        self.assertIn('$rdsStatus -eq "starting"', down_source)
        self.assertIn('Wait-ForRdsStatus "available"', down_source)

    def test_down_suspends_scaling_before_scaling_service_to_zero(self) -> None:
        source = DOWN_SCRIPT.read_text(encoding="utf-8")

        self.assertIn("Suspend-BackendAutoScaling\naws ecs update-service", source)

    def test_up_restores_capacity_before_service_then_resumes_scaling(self) -> None:
        source = UP_SCRIPT.read_text(encoding="utf-8")
        execution = source.split('Wait-ForRdsStatus "available"\n\n', 1)[1]

        self.assertIn(
            'Restore-BackendAutoScalingCapacity\naws ecs update-service',
            execution,
        )
        self.assertLess(
            execution.index("Restore-BackendAutoScalingCapacity"),
            execution.index("aws ecs update-service"),
        )
        self.assertLess(
            execution.index("aws ecs update-service"),
            execution.index("Resume-BackendAutoScaling"),
        )
        self.assertLess(
            execution.index("Resume-BackendAutoScaling"),
            execution.index('Write-Host "Production compute is up.'),
        )

    def test_autoscaling_does_not_take_persistent_infrastructure_ownership(self) -> None:
        source = AUTOSCALING_CONFIGURATION.read_text(encoding="utf-8")

        self.assertIsNone(re.search(r"^\s*count\s*=", source, re.MULTILINE))
        self.assertNotIn("aws_ecs_service", source)
        self.assertIn("축소가 완료된 뒤 5분", source)
        self.assertIn('required_version = ">= 1.10.0"', TERRAFORM_VERSIONS.read_text(encoding="utf-8"))
        for filename in (
            "generated-alb-ecs.tf",
            "generated-dns-listener-rule.tf",
            "generated-network.tf",
            "generated-rds.tf",
            "generated-routes.tf",
            "generated-security-groups.tf",
            "imports.tf",
            "moved.tf",
        ):
            self.assertFalse((AUTOSCALING_CONFIGURATION.parent / filename).exists(), filename)

    def test_terraform_apply_preserves_runtime_off_state(self) -> None:
        source = AUTOSCALING_CONFIGURATION.read_text(encoding="utf-8")
        lifecycle = re.search(
            r"lifecycle\s*\{\s*ignore_changes\s*=\s*\[(?P<attributes>.*?)\]\s*\}",
            source,
            re.DOTALL,
        )

        self.assertIsNotNone(lifecycle)
        self.assertIn("min_capacity", lifecycle.group("attributes"))
        self.assertIn("suspended_state", lifecycle.group("attributes"))


if __name__ == "__main__":
    unittest.main()
