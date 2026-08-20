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

    def test_automatic_deployment_requires_the_current_repository_and_main_sha(self):
        self.assertIn(
            "github.event.workflow_run.head_repository.full_name == github.repository", self.workflow)
        self.assertIn('repos/$GITHUB_REPOSITORY/git/ref/heads/main', self.workflow)
        self.assertIn('[ "$current_main_sha" != "$WORKFLOW_SHA" ]', self.workflow)
        self.assertIn('Skipping stale CI revision $WORKFLOW_SHA', self.workflow)

    def test_production_approval_and_activation_gates_are_preserved(self):
        self.assertIn("environment: production", self.workflow)
        self.assertIn("vars.PRODUCTION_ECS_DEPLOYMENT_ENABLED == 'true'", self.workflow)
        self.assertIn("Verify selected main revision after production approval", self.workflow)
        self.assertIn('[ "$current_main_sha" != "$DEPLOY_SHA" ]', self.workflow)
        self.assertIn('Skipping stale CI revision $DEPLOY_SHA after production approval', self.workflow)

    def test_approval_revalidation_covers_manual_deployments(self):
        approval_step = self.workflow.split(
            "- name: Verify selected main revision after production approval", 1
        )[1].split("- name: Validate deployment configuration", 1)[0]

        self.assertNotIn("if:", approval_step)
        self.assertIn("DEPLOY_SHA: ${{ needs.verify-source.outputs.image_tag }}", approval_step)
        self.assertIn("compare/$DEPLOY_SHA...main", approval_step)
        self.assertIn(
            'actions/runs?head_sha=$DEPLOY_SHA&event=push&status=completed', approval_step
        )

        deploy_job = self.workflow.split("  deploy:", 1)[1].split("    steps:", 1)[0]
        self.assertIn("actions: read", deploy_job)

    def test_manual_deployment_validates_main_history_and_exact_ci(self):
        self.assertIn("permissions:\n      actions: read\n      contents: read", self.workflow)
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

    def test_llm_runtime_flag_is_preserved_from_current_task_definition(self):
        self.assertIn('llm_enabled=$(jq -r --arg container "$ECS_CONTAINER_NAME"', self.workflow)
        self.assertIn('value: $llm_enabled', self.workflow)
        self.assertIn('| .value][0] // "false"', self.workflow)
        self.assertNotIn('| .value][0] // "true"', self.workflow)
        self.assertNotIn('{name: "MIRIYUM_STORE_SEARCH_LLM_ENABLED", value: "true"}', self.workflow)

    def test_cd_replaces_the_live_llm_timeout_with_the_approved_production_value(self):
        self.assertIn(
            '.name != "MIRIYUM_STORE_SEARCH_LLM_RESPONSE_TIMEOUT_MS"', self.workflow
        )
        self.assertIn(
            '{name: "MIRIYUM_STORE_SEARCH_LLM_RESPONSE_TIMEOUT_MS", value: "5000"}',
            self.workflow,
        )

    def test_openai_secret_is_added_only_when_llm_is_enabled(self):
        self.assertIn('if $llm_enabled == "true" then', self.workflow)
        self.assertIn('{name: "OPENAI_API_KEY", valueFrom: $openai_parameter_arn}', self.workflow)
        self.assertIn('else [] end', self.workflow)

    def test_runtime_config_defaults_to_disabled_and_injects_only_when_enabled(self):
        self.assertIn("RUNTIME_CONFIG_SECRET_NAME: miriyum/production/backend-runtime-config", self.workflow)
        self.assertIn('select(.name == "MIRIYUM_RUNTIME_CONFIG_ENABLED") | .value][0] // "false"', self.workflow)
        self.assertIn('if $runtime_config_enabled == "true" then', self.workflow)
        self.assertIn('else [] end', self.workflow)
        self.assertIn("aws secretsmanager describe-secret", self.workflow)
        self.assertIn("SPRING_APPLICATION_JSON", self.workflow)
        self.assertIn("runtime_config_secret_arn", self.workflow)

    def test_backend_ci_runs_the_workflow_contract_test(self):
        self.assertIn("Verify production ECS CD workflow contract", self.backend_ci)
        self.assertIn("python3 scripts/test-production-ecs-cd-workflow.py", self.backend_ci)


if __name__ == "__main__":
    unittest.main()
