#!/usr/bin/env python3
"""Guards Terraform references required to recreate production after an OFF apply."""

from pathlib import Path
import unittest


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
ALB_ECS = REPOSITORY_ROOT / "infra/terraform/production/generated-alb-ecs.tf"
NETWORK = REPOSITORY_ROOT / "infra/terraform/production/generated-network.tf"


class ProductionTerraformLifecycleContractTest(unittest.TestCase):
    def test_recreated_listeners_reference_recreated_resources(self) -> None:
        source = ALB_ECS.read_text(encoding="utf-8")

        self.assertEqual(2, source.count("load_balancer_arn                    = aws_lb.production[0].arn"))
        self.assertIn("arn    = aws_lb_target_group.backend_green[0].arn", source)
        self.assertIn("arn    = aws_lb_target_group.backend_blue[0].arn", source)
        self.assertNotIn("loadbalancer/app/miriyum-prod-alb/", source)
        self.assertNotIn("targetgroup/miriyum-prod-backend", source)

    def test_recreated_nat_does_not_reuse_deleted_network_interface(self) -> None:
        source = NETWORK.read_text(encoding="utf-8")

        self.assertIn("allocation_id            = aws_eip.production_nat[0].id", source)
        self.assertNotIn("network_interface", source)


if __name__ == "__main__":
    unittest.main()
