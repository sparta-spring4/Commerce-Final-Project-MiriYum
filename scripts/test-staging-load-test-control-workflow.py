import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CONTROL_WORKFLOW_PATH = ROOT / ".github" / "workflows" / "staging-load-test-control.yml"
BACKEND_CD_PATH = ROOT / ".github" / "workflows" / "backend-cd.yml"
BACKEND_CI_PATH = ROOT / ".github" / "workflows" / "backend-ci.yml"


class StagingLoadTestControlWorkflowContractTest(unittest.TestCase):
    def setUp(self):
        self.control_workflow = CONTROL_WORKFLOW_PATH.read_text(encoding="utf-8")
        self.backend_cd = BACKEND_CD_PATH.read_text(encoding="utf-8")
        self.backend_ci = BACKEND_CI_PATH.read_text(encoding="utf-8")

    def test_control_workflow_exposes_only_fixed_actions(self):
        self.assertIn("name: Staging Load-Test Control", self.control_workflow)
        self.assertIn("workflow_dispatch:", self.control_workflow)
        self.assertIn("enable-load-test", self.control_workflow)
        self.assertIn("disable-load-test", self.control_workflow)
        self.assertIn("safe-recovery", self.control_workflow)
        self.assertNotIn("environment_key", self.control_workflow)
        self.assertNotIn("environment_value", self.control_workflow)
        self.assertNotIn("shell_command", self.control_workflow)

    def test_control_workflow_validates_immutable_sha_and_public_ipv4(self):
        self.assertIn("^[0-9a-f]{40}$", self.control_workflow)
        self.assertIn("Invalid immutable image tag", self.control_workflow)
        self.assertIn("Invalid staging load-test source IP", self.control_workflow)
        self.assertIn("parts[0] === 10", self.control_workflow)
        self.assertIn("parts[0] === 127", self.control_workflow)
        self.assertIn("parts[0] === 192 && parts[1] === 168", self.control_workflow)

    def test_control_workflow_delegates_only_to_staging_cd(self):
        self.assertIn("uses: ./.github/workflows/backend-cd.yml", self.control_workflow)
        self.assertIn("actions: read", self.control_workflow)
        self.assertIn("rate_limit_exception: ${{ inputs.action == 'enable-load-test' && 'enable' || 'disable' }}", self.control_workflow)
        self.assertIn("runtime_recovery_mode: ${{ inputs.action == 'safe-recovery' && 'safe-disable' || 'preserve' }}", self.control_workflow)
        self.assertNotIn("AWS-RunShellScript", self.control_workflow)
        self.assertNotIn("aws ssm send-command", self.control_workflow)
        self.assertNotIn("gh workflow run", self.control_workflow)

    def test_backend_cd_accepts_only_fixed_safe_recovery_mode(self):
        self.assertIn("runtime_recovery_mode:", self.backend_cd)
        self.assertIn("safe-disable", self.backend_cd)
        self.assertIn("RUNTIME_RECOVERY_MODE", self.backend_cd)
        self.assertIn("Unsupported runtime recovery mode", self.backend_cd)
        self.assertIn("MIRIYUM_RUNTIME_CONFIG_ENABLED=false", self.backend_cd)
        self.assertIn("MIRIYUM_PAYMENT_ENABLED=false", self.backend_cd)
        self.assertIn("MIRIYUM_STORAGE_S3_ENABLED=false", self.backend_cd)
        self.assertIn("MIRIYUM_STORE_SEARCH_LLM_ENABLED=false", self.backend_cd)
        self.assertNotIn("runtime_recovery_key", self.backend_cd)
        self.assertNotIn("runtime_recovery_value", self.backend_cd)

    def test_backend_cd_accepts_a_dispatch_ip_without_printing_it(self):
        self.assertIn("load_test_source_ip:", self.backend_cd)
        self.assertIn("inputs.load_test_source_ip", self.backend_cd)
        self.assertIn("Invalid staging load-test source IP", self.backend_cd)
        self.assertNotIn("echo \"Staging load-test source IP: $STAGING_LOAD_TEST_SOURCE_IP\"", self.backend_cd)

    def test_backend_cd_requires_existing_images_for_reusable_deployment(self):
        self.assertIn('if [ "$EVENT_NAME" != "workflow_run" ]; then', self.backend_cd)
        self.assertIn("Manual or reusable deployment requires an existing immutable ECR image tag", self.backend_cd)
        self.assertIn("Manual or reusable deployment requires an existing immutable frontend ECR image tag", self.backend_cd)

    def test_backend_ci_runs_the_control_workflow_contract(self):
        self.assertIn("Verify staging load-test control workflow contract", self.backend_ci)
        self.assertIn("python3 scripts/test-staging-load-test-control-workflow.py", self.backend_ci)


if __name__ == "__main__":
    unittest.main()
