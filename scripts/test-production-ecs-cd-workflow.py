import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
WORKFLOW_PATH = ROOT / ".github" / "workflows" / "backend-production-ecs-cd.yml"
BACKEND_CI_PATH = ROOT / ".github" / "workflows" / "backend-ci.yml"


class ProductionEcsCdWorkflowContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow = WORKFLOW_PATH.read_text(encoding="utf-8")
        cls.backend_ci = BACKEND_CI_PATH.read_text(encoding="utf-8")

    def test_main_backend_ci_completion_is_the_automatic_source(self):
        self.assertIn("workflow_run:", self.workflow)
        self.assertIn('workflows: ["Backend CI"]', self.workflow)
        self.assertIn("branches: [main]", self.workflow)
        self.assertIn("github.event.workflow_run.head_branch == 'main'", self.workflow)
        self.assertIn("github.event.workflow_run.conclusion == 'success'", self.workflow)

    def test_manual_deployment_validates_main_history_and_exact_ci(self):
        self.assertIn("github.event_name == 'workflow_dispatch' && github.ref == 'refs/heads/dev'", self.workflow)
        self.assertNotIn("github.ref == 'refs/heads/main'", self.workflow)
        self.assertIn('compare/$image_tag...main', self.workflow)
        self.assertIn('Manual deployment SHA must be contained in the current main history.', self.workflow)
        self.assertIn('actions/runs?head_sha=$image_tag&event=push&status=completed', self.workflow)
        self.assertIn('.name == "Backend CI" and .head_branch == "main" and .conclusion == "success"', self.workflow)
        self.assertIn('Manual deployment requires a successful Backend CI run for this main SHA.', self.workflow)

    def test_production_deployment_requires_immutable_ecr_tags(self):
        self.assertIn("Require immutable ECR image tags", self.workflow)
        self.assertIn("imageTagMutability", self.workflow)
        self.assertIn('Production deployment requires an IMMUTABLE ECR repository.', self.workflow)

    def test_task_definition_rejects_missing_or_wrong_backend_container(self):
        self.assertIn("Expected exactly one $ECS_CONTAINER_NAME container", self.workflow)
        self.assertIn("updated_container_count", self.workflow)
        self.assertIn("updated_image", self.workflow)
        self.assertIn('The next task definition does not contain the selected backend image.', self.workflow)

    def test_backend_ci_runs_the_workflow_contract_test(self):
        self.assertIn("Verify production ECS CD workflow contract", self.backend_ci)
        self.assertIn("python3 scripts/test-production-ecs-cd-workflow.py", self.backend_ci)


if __name__ == "__main__":
    unittest.main()
