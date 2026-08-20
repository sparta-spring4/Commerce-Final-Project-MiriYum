#!/usr/bin/env python3
"""Guards the approved production compute OFF/ON boundary."""

from pathlib import Path
import unittest


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
DOWN_SCRIPT = REPOSITORY_ROOT / "infra/terraform/production/scripts/production-down.ps1"
UP_SCRIPT = REPOSITORY_ROOT / "infra/terraform/production/scripts/production-up.ps1"


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


if __name__ == "__main__":
    unittest.main()
