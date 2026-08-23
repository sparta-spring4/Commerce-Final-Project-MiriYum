import subprocess
import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CONTROL_WORKFLOW_PATH = ROOT / ".github" / "workflows" / "staging-load-test-control.yml"
VALKEY_CONTROL_WORKFLOW_PATH = ROOT / ".github" / "workflows" / "staging-valkey-control.yml"
BACKEND_CD_PATH = ROOT / ".github" / "workflows" / "backend-cd.yml"
BACKEND_CI_PATH = ROOT / ".github" / "workflows" / "backend-ci.yml"
IP_VALIDATOR_PATH = ROOT / "scripts" / "validate-staging-load-test-source-ip.js"
VALKEY_CONTROL_TEST_PATH = ROOT / "scripts" / "test-staging-valkey-control.py"


class StagingLoadTestControlWorkflowContractTest(unittest.TestCase):
    def setUp(self):
        self.control_workflow = CONTROL_WORKFLOW_PATH.read_text(encoding="utf-8")
        self.valkey_control_workflow = VALKEY_CONTROL_WORKFLOW_PATH.read_text(encoding="utf-8")
        self.backend_cd = BACKEND_CD_PATH.read_text(encoding="utf-8")
        self.backend_ci = BACKEND_CI_PATH.read_text(encoding="utf-8")

    def test_control_workflow_exposes_only_fixed_actions(self):
        self.assertIn("name: Staging Load-Test Control", self.control_workflow)
        self.assertIn("workflow_dispatch:", self.control_workflow)
        self.assertIn("enable-load-test", self.control_workflow)
        self.assertIn("disable-load-test", self.control_workflow)
        self.assertIn("enable-sse", self.control_workflow)
        self.assertIn("disable-sse", self.control_workflow)
        self.assertIn("safe-recovery", self.control_workflow)
        self.assertIn("interrupt-valkey", self.control_workflow)
        self.assertIn("recover-valkey", self.control_workflow)
        self.assertNotIn("environment_key", self.control_workflow)
        self.assertNotIn("environment_value", self.control_workflow)
        self.assertNotIn("shell_command", self.control_workflow)

    def test_control_workflow_validates_immutable_sha_and_public_ipv4(self):
        self.assertIn("^[0-9a-f]{40}$", self.control_workflow)
        self.assertIn("Invalid immutable image tag", self.control_workflow)
        self.assertIn("Invalid staging load-test source IP", self.control_workflow)
        self.assertIn("validate-staging-load-test-source-ip.js", self.control_workflow)
        self.assertIn("validate-staging-load-test-source-ip.js", self.backend_cd)

    def test_control_validation_checks_out_the_validator_before_running_it(self):
        checkout = "uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1"
        validator = "validate-staging-load-test-source-ip.js"
        self.assertIn(checkout, self.control_workflow)
        self.assertLess(self.control_workflow.index(checkout), self.control_workflow.index(validator))

    def test_rejects_special_use_ipv4_ranges_and_accepts_global_ipv4(self):
        self.assertTrue(IP_VALIDATOR_PATH.exists())

        for address in (
            "192.0.2.1",
            "198.51.100.1",
            "203.0.113.1",
            "198.18.0.1",
            "100.64.0.1",
        ):
            result = subprocess.run(
                ["node", str(IP_VALIDATOR_PATH), address],
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertNotEqual(result.returncode, 0, address)

        result = subprocess.run(
            ["node", str(IP_VALIDATOR_PATH), "8.8.8.8"],
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_control_workflow_delegates_only_to_reviewed_reusable_workflows(self):
        self.assertIn("uses: ./.github/workflows/backend-cd.yml", self.control_workflow)
        self.assertIn("uses: ./.github/workflows/staging-valkey-control.yml", self.control_workflow)
        self.assertIn("actions: read", self.control_workflow)
        self.assertIn("rate_limit_exception: ${{ inputs.action == 'enable-load-test' && 'enable' || inputs.action == 'disable-load-test' && 'disable' || inputs.action == 'safe-recovery' && 'disable' || 'preserve' }}", self.control_workflow)
        self.assertIn("runtime_recovery_mode: ${{ inputs.action == 'safe-recovery' && 'safe-disable' || 'preserve' }}", self.control_workflow)
        self.assertIn("runtime_config_mode: ${{ inputs.action == 'enable-sse' && 'enable' || inputs.action == 'disable-sse' && 'disable' || 'preserve' }}", self.control_workflow)
        self.assertNotIn("AWS-RunShellScript", self.control_workflow)
        self.assertNotIn("aws ssm send-command", self.control_workflow)
        self.assertNotIn("gh workflow run", self.control_workflow)

    def test_valkey_control_is_staging_only_and_accepts_no_arbitrary_command_or_duration(self):
        self.assertIn("name: Staging Valkey Control", self.valkey_control_workflow)
        self.assertIn("workflow_call:", self.valkey_control_workflow)
        self.assertNotIn("workflow_dispatch:", self.valkey_control_workflow)
        self.assertIn("environment: staging", self.valkey_control_workflow)
        self.assertIn("interrupt", self.valkey_control_workflow)
        self.assertIn("recover", self.valkey_control_workflow)
        self.assertNotIn("duration:", self.valkey_control_workflow)
        self.assertNotIn("shell_command", self.valkey_control_workflow)
        self.assertNotIn("production", self.valkey_control_workflow.lower())

    def test_valkey_control_requires_the_reviewed_dev_caller_before_oidc(self):
        self.assertIn("Staging Valkey controls must run from refs/heads/dev", self.control_workflow)
        expected_caller = (
            "$GITHUB_REPOSITORY/.github/workflows/"
            "staging-load-test-control.yml@refs/heads/dev"
        )
        self.assertIn(expected_caller, self.valkey_control_workflow)
        self.assertIn('CALLER_WORKFLOW_REF: ${{ github.workflow_ref }}', self.valkey_control_workflow)
        self.assertIn('if [ "$GITHUB_REF" != "refs/heads/dev" ]', self.valkey_control_workflow)
        self.assertIn("ref: refs/heads/dev", self.valkey_control_workflow)
        caller_check = self.valkey_control_workflow.index("expected_caller=")
        oidc = self.valkey_control_workflow.index("Configure AWS credentials through OIDC")
        self.assertLess(caller_check, oidc)

    def test_valkey_control_requires_current_successful_staging_backend_sha(self):
        self.assertIn("staging-backend", self.valkey_control_workflow)
        self.assertIn("deployments?environment=", self.valkey_control_workflow)
        self.assertIn("statuses?per_page=1", self.valkey_control_workflow)
        self.assertIn("does not match the latest successful staging backend deployment", self.valkey_control_workflow)
        self.assertIn("^[0-9a-f]{40}$", self.valkey_control_workflow)

    def test_valkey_control_runs_only_the_reviewed_script_through_ssm(self):
        self.assertIn("scripts/staging-valkey-control.sh", self.valkey_control_workflow)
        self.assertIn("AWS-RunShellScript", self.valkey_control_workflow)
        self.assertIn("aws ssm send-command", self.valkey_control_workflow)
        self.assertIn("VALKEY_CONTROL_ACTION", self.valkey_control_workflow)
        self.assertIn("StandardOutputContent", self.valkey_control_workflow)
        self.assertIn("StandardErrorContent", self.valkey_control_workflow)

    def test_valkey_control_failure_logs_only_bounded_allowlisted_diagnostics(self):
        self.assertIn("ResponseCode", self.valkey_control_workflow)
        self.assertIn("staging_valkey_control_failed", self.valkey_control_workflow)
        self.assertIn("remote-command-failed", self.valkey_control_workflow)
        self.assertIn("grep -E", self.valkey_control_workflow)
        self.assertNotIn('echo "$invocation"', self.valkey_control_workflow)
        self.assertNotIn('printf \'%s\\n\' "$standard_error"', self.valkey_control_workflow)

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

    def test_backend_cd_accepts_only_fixed_runtime_config_mode(self):
        self.assertIn("runtime_config_mode:", self.backend_cd)
        self.assertIn("RUNTIME_CONFIG_MODE", self.backend_cd)
        self.assertIn("Unsupported runtime config mode", self.backend_cd)
        self.assertIn("MIRIYUM_RUNTIME_CONFIG_ENABLED=true", self.backend_cd)
        self.assertIn("MIRIYUM_RUNTIME_CONFIG_ENABLED=false", self.backend_cd)
        self.assertNotIn("runtime_config_key", self.backend_cd)
        self.assertNotIn("runtime_config_value", self.backend_cd)

    def test_backend_cd_accepts_a_dispatch_ip_without_printing_it(self):
        self.assertIn("load_test_source_ip:", self.backend_cd)
        self.assertIn("inputs.load_test_source_ip", self.backend_cd)
        self.assertIn("Invalid staging load-test source IP", self.backend_cd)
        self.assertNotIn("echo \"Staging load-test source IP: $STAGING_LOAD_TEST_SOURCE_IP\"", self.backend_cd)

    def test_backend_cd_resolves_and_encodes_load_test_ip_only_when_enabling_exception(self):
        self.assertIn(
            "STAGING_LOAD_TEST_SOURCE_IP: ${{ inputs.rate_limit_exception == 'enable' && (inputs.load_test_source_ip || secrets.MIRIYUM_STAGING_LOAD_TEST_SOURCE_IP) || '' }}",
            self.backend_cd,
        )
        self.assertIn('if [ "$RATE_LIMIT_EXCEPTION" = "enable" ]; then', self.backend_cd)
        self.assertIn('load_test_source_ip_base64=$(printf %s "$STAGING_LOAD_TEST_SOURCE_IP" | base64 --wrap=0)', self.backend_cd)
        self.assertNotIn(
            "STAGING_LOAD_TEST_SOURCE_IP: ${{ inputs.load_test_source_ip || secrets.MIRIYUM_STAGING_LOAD_TEST_SOURCE_IP }}",
            self.backend_cd,
        )

    def test_backend_cd_keeps_control_inputs_private_to_reusable_calls(self):
        dispatch = self.backend_cd.split("workflow_dispatch:", 1)[1].split("permissions:", 1)[0]
        self.assertNotIn("rate_limit_exception:", dispatch)
        self.assertNotIn("load_test_source_ip:", dispatch)
        self.assertNotIn("runtime_recovery_mode:", dispatch)
        self.assertNotIn("runtime_config_mode:", dispatch)

    def test_backend_cd_allows_non_preserve_control_only_from_the_fixed_control_workflow(self):
        self.assertIn("CALLER_WORKFLOW_REF", self.backend_cd)
        self.assertIn(".github/workflows/staging-load-test-control.yml@", self.backend_cd)
        self.assertIn("Non-preserve staging control inputs are allowed only", self.backend_cd)

    def test_enable_deploys_with_ip_and_recovers_original_runtime_on_failure(self):
        deploy_command = "AWS_REGION='$AWS_REGION' BACKEND_IMAGE='$image_uri' FRONTEND_IMAGE='$frontend_image_uri' /opt/miriyum/deploy.sh"
        enable_command = "Staging load-test source IP enabled."
        self.assertLess(self.backend_cd.index(enable_command), self.backend_cd.index(deploy_command))
        self.assertIn("backup_env=", self.backend_cd)
        self.assertIn("restore_env", self.backend_cd)
        self.assertIn("trap restore_env EXIT HUP INT TERM", self.backend_cd)
        self.assertIn('cp \\"\\$backup_env\\" /opt/miriyum/.env', self.backend_cd)
        self.assertIn("previous_backend_image=", self.backend_cd)
        self.assertIn("previous_frontend_image=", self.backend_cd)
        self.assertIn("backup-compose.yml", self.backend_cd)
        self.assertIn('FRONTEND_IMAGE=\\"\\$previous_frontend_image\\"', self.backend_cd)
        self.assertIn('docker compose --env-file /opt/miriyum/.env -f \\"\\$backup_dir/backup-compose.yml\\" up -d --force-recreate --remove-orphans || recovery_status=\\$?', self.backend_cd)
        self.assertIn("recovery_attempt=0", self.backend_cd)
        self.assertIn("Recovered backend did not become healthy", self.backend_cd)
        self.assertLess(self.backend_cd.index("trap restore_env EXIT HUP INT TERM"), self.backend_cd.index("echo '$compose_base64' | base64 --decode > /opt/miriyum/docker-compose.prod.yml"))

    def test_safe_disable_failure_keeps_runtime_flags_off_and_restores_the_full_stack(self):
        self.assertIn('cp -a /opt/miriyum/nginx \\"\\$backup_dir/nginx\\"', self.backend_cd)
        self.assertIn('rm -rf /opt/miriyum/nginx; cp -a \\"\\$backup_dir/nginx\\" /opt/miriyum/nginx', self.backend_cd)
        self.assertIn("safe-disable) sed -i '/^MIRIYUM_STAGING_LOAD_TEST_SOURCE_IP=/d; /^MIRIYUM_RUNTIME_CONFIG_ENABLED=/d; /^MIRIYUM_PAYMENT_ENABLED=/d; /^MIRIYUM_STORAGE_S3_ENABLED=/d; /^MIRIYUM_STORE_SEARCH_LLM_ENABLED=/d'", self.backend_cd)
        self.assertIn('MIRIYUM_RUNTIME_CONFIG_ENABLED=false\\nMIRIYUM_PAYMENT_ENABLED=false\\nMIRIYUM_STORAGE_S3_ENABLED=false\\nMIRIYUM_STORE_SEARCH_LLM_ENABLED=false\\n', self.backend_cd)
        self.assertIn('up -d --force-recreate --remove-orphans || recovery_status=\\$?', self.backend_cd)

    def test_enable_failure_or_cancellation_runs_state_preserving_cleanup(self):
        self.assertIn("disable-after-failed-enable", self.control_workflow)
        self.assertIn("inputs.action == 'enable-load-test' || inputs.action == 'enable-sse'", self.control_workflow)
        self.assertIn("needs.deploy.result == 'failure' || needs.deploy.result == 'cancelled'", self.control_workflow)
        self.assertIn("rate_limit_exception: ${{ inputs.action == 'enable-load-test' && 'disable' || 'preserve' }}", self.control_workflow)
        self.assertIn("runtime_config_mode: preserve", self.control_workflow)
        self.assertNotIn("runtime_config_mode: ${{ inputs.action == 'enable-sse' && 'disable' || 'preserve' }}", self.control_workflow)

    def test_failed_sse_enable_restores_the_pre_deployment_runtime_state(self):
        restore_handler = self.backend_cd.split('"backup_env=', 1)[1].split('",\n', 1)[0]
        self.assertIn('cp \\"\\$backup_env\\" /opt/miriyum/.env', restore_handler)
        self.assertNotIn("case '$RUNTIME_CONFIG_MODE' in enable)", restore_handler)

    def test_runtime_config_toggle_is_applied_before_the_deployment(self):
        deploy_command = "AWS_REGION='$AWS_REGION' BACKEND_IMAGE='$image_uri' FRONTEND_IMAGE='$frontend_image_uri' /opt/miriyum/deploy.sh"
        enable_command = "Staging runtime config enabled."
        disable_command = "Staging runtime config disabled."
        self.assertLess(self.backend_cd.index(enable_command), self.backend_cd.index(deploy_command))
        self.assertLess(self.backend_cd.index(disable_command), self.backend_cd.index(deploy_command))

    def test_cleanup_cancels_and_waits_for_the_original_ssm_command(self):
        self.assertIn("ssm_command_id:", self.backend_cd)
        self.assertIn("jobs.deploy.outputs.ssm_command_id", self.backend_cd)
        self.assertIn("cancel_ssm_command_id:", self.backend_cd)
        self.assertIn("aws ssm cancel-command", self.backend_cd)
        self.assertIn("Original SSM command reached terminal state", self.backend_cd)
        self.assertIn("cancel_ssm_command_id: ${{ needs.deploy.outputs.ssm_command_id }}", self.control_workflow)

    def test_backend_cd_requires_existing_images_for_reusable_deployment(self):
        self.assertIn('if [ "$EVENT_NAME" != "workflow_run" ]; then', self.backend_cd)
        self.assertIn("Manual or reusable deployment requires an existing immutable ECR image tag", self.backend_cd)
        self.assertIn("Manual or reusable deployment requires an existing immutable frontend ECR image tag", self.backend_cd)

    def test_backend_ci_runs_the_control_workflow_contract(self):
        self.assertIn("Verify staging load-test control workflow contract", self.backend_ci)
        self.assertIn("python3 scripts/test-staging-load-test-control-workflow.py", self.backend_ci)

    def test_valkey_script_runtime_contract(self):
        result = subprocess.run(
            [sys.executable, str(VALKEY_CONTROL_TEST_PATH)],
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
