import json
import shutil
import subprocess
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

    def test_live_waiting_history_cursor_secret_mapping_is_verified_before_image_rollout(self):
        step_name = "- name: Verify live waiting history cursor secret mapping"
        self.assertIn(step_name, self.workflow)
        preflight = self.workflow.split(
            step_name, 1
        )[1].split("- name:", 1)[0]

        self.assertIn("aws ecs describe-services", preflight)
        self.assertIn("aws ecs describe-task-definition", preflight)
        self.assertIn('MIRIYUM_WAITING_HISTORY_CURSOR_SECRET', preflight)
        self.assertIn("waiting_cursor_mapping_count", preflight)
        self.assertIn('[ \"$waiting_cursor_mapping_count\" != \"1\" ]', preflight)
        self.assertIn(':MIRIYUM_WAITING_HISTORY_CURSOR_SECRET::', preflight)
        self.assertIn("Production live task must map exactly one waiting history cursor secret", preflight)
        self.assertNotIn("get-secret-value", preflight)
        self.assertLess(
            self.workflow.index(step_name),
            self.workflow.index("- name: Log in to Amazon ECR"),
        )

    def test_live_waiting_history_cursor_secret_uses_the_application_secret_arn(self):
        preflight = self.workflow.split(
            "- name: Verify live waiting history cursor secret mapping", 1
        )[1].split("- name:", 1)[0]

        self.assertIn('select(.name == "MIRIYUM_DB_URL")', preflight)
        self.assertIn("application_secret_mapping_count", preflight)
        self.assertIn('[ "$application_secret_mapping_count" != "1" ]', preflight)
        self.assertIn("application_secret_arn", preflight)
        self.assertIn(
            '"${application_secret_arn}:MIRIYUM_WAITING_HISTORY_CURSOR_SECRET::"',
            preflight,
        )
        self.assertIn(
            "Waiting history cursor secret must use the production application secret ARN",
            preflight,
        )

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

    def test_cd_validates_and_replaces_the_live_qr_storage_generation(self):
        self.assertIn(
            'QR_STORAGE_GENERATION: ${{ vars.MIRIYUM_QR_STORAGE_GENERATION }}',
            self.workflow,
        )
        self.assertIn('"$QR_STORAGE_GENERATION" =~ ^[A-Za-z0-9._-]{1,64}$', self.workflow)
        self.assertIn('--arg qr_storage_generation "$QR_STORAGE_GENERATION"', self.workflow)
        self.assertIn('.name != "MIRIYUM_QR_STORAGE_GENERATION"', self.workflow)
        self.assertIn(
            '{name: "MIRIYUM_QR_STORAGE_GENERATION", value: $qr_storage_generation}',
            self.workflow,
        )

    def test_openai_secret_is_added_only_when_llm_is_enabled(self):
        self.assertIn('if $llm_enabled == "true" then', self.workflow)
        self.assertIn('{name: "OPENAI_API_KEY", valueFrom: $openai_parameter_arn}', self.workflow)
        self.assertIn('else [] end', self.workflow)

    def test_payment_activation_uses_explicit_environment_values_and_excludes_webhooks(self):
        self.assertIn(
            'payment_runtime:', self.workflow
        )
        self.assertIn(
            'Payment runtime: preserve (default), enable, or disable', self.workflow
        )
        self.assertIn(
            'PAYMENT_RUNTIME: ${{ inputs.payment_runtime || \'preserve\' }}', self.workflow
        )
        self.assertIn(
            'MIRIYUM_PORTONE_STORE_ID: ${{ vars.MIRIYUM_PORTONE_STORE_ID }}', self.workflow
        )
        self.assertNotIn('MIRIYUM_PAYMENT_ENABLED: ${{ vars.', self.workflow)
        self.assertIn('payment_runtime must be preserve, enable, or disable.', self.workflow)
        self.assertIn('payment_runtime="$PAYMENT_RUNTIME"', self.workflow)
        self.assertIn(
            'MIRIYUM_PORTONE_STORE_ID must be configured when payment is enabled.',
            self.workflow,
        )
        self.assertIn('if [ "$payment_runtime" = "enable" ] && [ -z "$MIRIYUM_PORTONE_STORE_ID" ]; then', self.workflow)
        self.assertIn('--arg payment_runtime "$payment_runtime"', self.workflow)
        self.assertIn('--arg portone_store_id "$MIRIYUM_PORTONE_STORE_ID"', self.workflow)
        self.assertIn('.name != "MIRIYUM_PORTONE_WEBHOOK_ENABLED"', self.workflow)
        self.assertIn('if $payment_runtime == "preserve" then true', self.workflow)
        self.assertIn('.name != "MIRIYUM_PAYMENT_ENABLED"', self.workflow)
        self.assertIn('.name != "MIRIYUM_PORTONE_STORE_ID"', self.workflow)
        self.assertIn('.name != "MIRIYUM_PAYMENT_CURSOR_SECRET"', self.workflow)
        self.assertIn('.name != "MIRIYUM_PORTONE_API_SECRET"', self.workflow)
        self.assertIn('{name: "MIRIYUM_PAYMENT_ENABLED", value: "true"}', self.workflow)
        self.assertIn('{name: "MIRIYUM_PORTONE_WEBHOOK_ENABLED", value: "false"}', self.workflow)
        self.assertIn('{name: "MIRIYUM_PORTONE_STORE_ID", value: $portone_store_id}', self.workflow)
        self.assertIn(
            '{name: "MIRIYUM_PAYMENT_CURSOR_SECRET", valueFrom: $payment_cursor_secret_arn}',
            self.workflow,
        )
        self.assertIn(
            '{name: "MIRIYUM_PORTONE_API_SECRET", valueFrom: $portone_api_secret_arn}',
            self.workflow,
        )
        self.assertNotIn('MIRIYUM_PORTONE_WEBHOOK_SECRET", valueFrom: $', self.workflow)

    def test_preserve_keeps_live_payment_values_and_selectors_when_repository_values_differ(self):
        """A normal main deployment must not reconstruct payment runtime from repository vars."""
        self.assertIn('--arg payment_runtime "$payment_runtime"', self.workflow)
        self.assertIn('if $payment_runtime == "preserve" then', self.workflow)

        jq = shutil.which("jq")
        if jq is None:
            self.skipTest("jq is required for the workflow transformation fixture")

        filter_start = self.workflow.index('--arg aws_region "$AWS_REGION" \'\n')
        filter_start = self.workflow.index("\n", filter_start) + 1
        filter_end = self.workflow.index("\n          ' current-task-definition.json", filter_start)
        jq_filter = self.workflow[filter_start:filter_end]
        source = {
            "containerDefinitions": [{
                "name": "backend",
                "image": "old-image",
                "environment": [
                    {"name": "MIRIYUM_PAYMENT_ENABLED", "value": "true"},
                    {"name": "MIRIYUM_PORTONE_STORE_ID", "value": "store-live"},
                    {"name": "MIRIYUM_PORTONE_WEBHOOK_ENABLED", "value": "true"},
                    {"name": "OTHER", "value": "kept"},
                ],
                "secrets": [
                    {"name": "MIRIYUM_PAYMENT_CURSOR_SECRET", "valueFrom": "arn:live:cursor"},
                    {"name": "MIRIYUM_PORTONE_API_SECRET", "valueFrom": "arn:live:api"},
                    {"name": "MIRIYUM_PORTONE_WEBHOOK_SECRET", "valueFrom": "arn:live:webhook"},
                    {"name": "OTHER_SECRET", "valueFrom": "arn:other"},
                ],
            }]
        }
        command = [
            jq,
            "--arg", "image", "new-image",
            "--arg", "container", "backend",
            "--arg", "runtime_config_secret_arn", "arn:runtime",
            "--arg", "openai_parameter_arn", "arn:openai",
            "--arg", "llm_enabled", "false",
            "--arg", "qr_storage_generation", "generation",
            "--arg", "runtime_config_enabled", "false",
            "--arg", "storage_s3_enabled", "false",
            "--arg", "storage_s3_reconciliation_enabled", "false",
            "--arg", "storage_s3_bucket_parameter_arn", "arn:bucket",
            "--arg", "payment_runtime", "preserve",
            "--arg", "payment_enabled", "false",
            "--arg", "portone_store_id", "store-from-repository",
            "--arg", "payment_cursor_secret_arn", "arn:repository:cursor",
            "--arg", "portone_api_secret_arn", "arn:repository:api",
            "--arg", "aws_region", "ap-northeast-2",
            jq_filter,
        ]
        result = subprocess.run(command, input=json.dumps(source), text=True, capture_output=True, check=True)
        backend = json.loads(result.stdout)["containerDefinitions"][0]
        environment = {item["name"]: item["value"] for item in backend["environment"]}
        secrets = {item["name"]: item["valueFrom"] for item in backend["secrets"]}

        self.assertEqual("true", environment["MIRIYUM_PAYMENT_ENABLED"])
        self.assertEqual("store-live", environment["MIRIYUM_PORTONE_STORE_ID"])
        self.assertEqual("false", environment["MIRIYUM_PORTONE_WEBHOOK_ENABLED"])
        self.assertEqual("arn:live:cursor", secrets["MIRIYUM_PAYMENT_CURSOR_SECRET"])
        self.assertEqual("arn:live:api", secrets["MIRIYUM_PORTONE_API_SECRET"])
        self.assertNotIn("MIRIYUM_PORTONE_WEBHOOK_SECRET", secrets)

    def test_disable_explicitly_sets_payment_false_and_removes_payment_configuration(self):
        self.assertIn(
            'if $payment_runtime == "disable" then\n'
            '                    [{name: "MIRIYUM_PAYMENT_ENABLED", value: "false"}]',
            self.workflow,
        )
        jq = shutil.which("jq")
        if jq is None:
            self.skipTest("jq is required for the workflow transformation fixture")

        filter_start = self.workflow.index('--arg aws_region "$AWS_REGION" \'\n')
        filter_start = self.workflow.index("\n", filter_start) + 1
        filter_end = self.workflow.index("\n          ' current-task-definition.json", filter_start)
        jq_filter = self.workflow[filter_start:filter_end]
        source = {"containerDefinitions": [{
            "name": "backend",
            "environment": [
                {"name": "MIRIYUM_PAYMENT_ENABLED", "value": "true"},
                {"name": "MIRIYUM_PORTONE_STORE_ID", "value": "store-live"},
            ],
            "secrets": [
                {"name": "MIRIYUM_PAYMENT_CURSOR_SECRET", "valueFrom": "arn:live:cursor"},
                {"name": "MIRIYUM_PORTONE_API_SECRET", "valueFrom": "arn:live:api"},
            ],
        }]}
        command = [
            jq,
            "--arg", "image", "new-image", "--arg", "container", "backend",
            "--arg", "runtime_config_secret_arn", "arn:runtime",
            "--arg", "openai_parameter_arn", "arn:openai", "--arg", "llm_enabled", "false",
            "--arg", "qr_storage_generation", "generation",
            "--arg", "runtime_config_enabled", "false", "--arg", "storage_s3_enabled", "false",
            "--arg", "storage_s3_reconciliation_enabled", "false",
            "--arg", "storage_s3_bucket_parameter_arn", "arn:bucket",
            "--arg", "payment_runtime", "disable", "--arg", "portone_store_id", "store-from-repository",
            "--arg", "payment_cursor_secret_arn", "arn:repository:cursor",
            "--arg", "portone_api_secret_arn", "arn:repository:api",
            "--arg", "aws_region", "ap-northeast-2", jq_filter,
        ]
        result = subprocess.run(command, input=json.dumps(source), text=True, capture_output=True, check=True)
        backend = json.loads(result.stdout)["containerDefinitions"][0]
        environment = {item["name"]: item["value"] for item in backend["environment"]}
        secret_names = {item["name"] for item in backend["secrets"]}

        self.assertEqual("false", environment["MIRIYUM_PAYMENT_ENABLED"])
        self.assertNotIn("MIRIYUM_PORTONE_STORE_ID", environment)
        self.assertNotIn("MIRIYUM_PAYMENT_CURSOR_SECRET", secret_names)
        self.assertNotIn("MIRIYUM_PORTONE_API_SECRET", secret_names)

    def test_runtime_config_defaults_to_disabled_and_injects_only_when_enabled(self):
        self.assertIn("RUNTIME_CONFIG_SECRET_NAME: miriyum/production/backend-runtime-config", self.workflow)
        self.assertIn('select(.name == "MIRIYUM_RUNTIME_CONFIG_ENABLED") | .value][0] // "false"', self.workflow)
        self.assertIn('if $runtime_config_enabled == "true" then', self.workflow)
        self.assertIn('else [] end', self.workflow)
        self.assertIn("aws secretsmanager describe-secret", self.workflow)
        self.assertIn("SPRING_APPLICATION_JSON", self.workflow)
        self.assertIn("runtime_config_secret_arn", self.workflow)

    def test_manual_runtime_config_mode_controls_the_flag_and_whole_secret_mapping(self):
        self.assertIn("runtime_config:", self.workflow)
        self.assertIn(
            "Runtime config: preserve (default), enable, or disable", self.workflow)
        self.assertIn("RUNTIME_CONFIG_MODE: ${{ inputs.runtime_config || 'preserve' }}", self.workflow)
        self.assertIn('runtime_config_mode="$RUNTIME_CONFIG_MODE"', self.workflow)
        self.assertIn('preserve) runtime_config_enabled="$current_runtime_config_enabled" ;;', self.workflow)
        self.assertIn('enable) runtime_config_enabled="true" ;;', self.workflow)
        self.assertIn('disable) runtime_config_enabled="false" ;;', self.workflow)
        self.assertIn('runtime_config must be preserve, enable, or disable.', self.workflow)

        jq = shutil.which("jq")
        if jq is None:
            self.skipTest("jq is required for the workflow transformation fixture")

        filter_start = self.workflow.index('--arg aws_region "$AWS_REGION" \'\n')
        filter_start = self.workflow.index("\n", filter_start) + 1
        filter_end = self.workflow.index("\n          ' current-task-definition.json", filter_start)
        jq_filter = self.workflow[filter_start:filter_end]
        source = {"containerDefinitions": [{
            "name": "backend",
            "environment": [
                {"name": "MIRIYUM_RUNTIME_CONFIG_ENABLED", "value": "false"},
            ],
            "secrets": [],
        }]}

        def render(runtime_config_enabled):
            command = [
                jq,
                "--arg", "image", "new-image", "--arg", "container", "backend",
                "--arg", "runtime_config_secret_arn", "arn:runtime",
                "--arg", "openai_parameter_arn", "arn:openai", "--arg", "llm_enabled", "false",
                "--arg", "qr_storage_generation", "generation",
                "--arg", "runtime_config_enabled", runtime_config_enabled,
                "--arg", "storage_s3_enabled", "false",
                "--arg", "storage_s3_reconciliation_enabled", "false",
                "--arg", "storage_s3_bucket_parameter_arn", "arn:bucket",
                "--arg", "payment_runtime", "preserve", "--arg", "portone_store_id", "store",
                "--arg", "payment_cursor_secret_arn", "arn:payment:cursor",
                "--arg", "portone_api_secret_arn", "arn:payment:api",
                "--arg", "aws_region", "ap-northeast-2", jq_filter,
            ]
            result = subprocess.run(
                command, input=json.dumps(source), text=True, capture_output=True, check=True)
            return json.loads(result.stdout)["containerDefinitions"][0]

        enabled = render("true")
        enabled_environment = {item["name"]: item["value"] for item in enabled["environment"]}
        enabled_secrets = {item["name"]: item["valueFrom"] for item in enabled["secrets"]}
        self.assertEqual("true", enabled_environment["MIRIYUM_RUNTIME_CONFIG_ENABLED"])
        self.assertEqual("arn:runtime", enabled_secrets["SPRING_APPLICATION_JSON"])

        disabled = render("false")
        disabled_environment = {item["name"]: item["value"] for item in disabled["environment"]}
        disabled_secret_names = {item["name"] for item in disabled["secrets"]}
        self.assertEqual("false", disabled_environment["MIRIYUM_RUNTIME_CONFIG_ENABLED"])
        self.assertNotIn("SPRING_APPLICATION_JSON", disabled_secret_names)

    def test_ecs_stability_wait_polls_for_the_approved_rolling_deployment_budget(self):
        stability_step = self.workflow.split(
            "- name: Wait for ECS service stability", 1
        )[1].split("- name:", 1)[0]

        self.assertIn("for attempt in $(seq 1 80)", stability_step)
        self.assertIn("aws ecs describe-services", stability_step)
        self.assertIn("sleep 15", stability_step)
        self.assertIn("rolloutState", stability_step)
        self.assertIn('select(.rolloutState == "FAILED")', stability_step)
        self.assertIn('"$primary_rollout_state" = "COMPLETED"', stability_step)
        self.assertIn('"$primary_task_definition" = "$TASK_DEFINITION_ARN"', stability_step)
        self.assertIn("did not stabilize within the 20-minute deployment budget", stability_step)
        self.assertNotIn("aws ecs wait services-stable", stability_step)

    def test_deploy_job_timeout_covers_image_build_and_stability_wait_budget(self):
        deploy_job = self.workflow.split("  deploy:", 1)[1].split("    permissions:", 1)[0]

        self.assertIn("timeout-minutes: 50", deploy_job)

    def test_automatic_and_manual_sources_enforce_the_notification_writer_floor(self):
        self.assertIn(
            "NOTIFICATION_READ_MINIMUM_COMPATIBLE_SHA: "
            "515531e122ebbce13d8eead4a3ff15a94c25e0b3",
            self.workflow,
        )
        self.assertGreaterEqual(
            self.workflow.count("verify-ancestor"),
            2,
        )
        self.assertGreaterEqual(
            self.workflow.count("fetch-depth: 0"),
            2,
        )

    def test_completed_rollout_requires_task_and_target_lifecycle_evidence(self):
        capture_step = self.workflow.split(
            "- name: Capture previous ECS task identities", 1
        )[1].split("- name: Update ECS service", 1)[0]
        evidence_step = self.workflow.split(
            "- name: Verify production ECS replacement evidence", 1
        )[1]

        self.assertIn("aws ecs list-tasks", capture_step)
        self.assertIn("--desired-status RUNNING", capture_step)
        self.assertIn("--desired-status STOPPED", capture_step)
        self.assertIn("aws ecs describe-tasks", capture_step)
        self.assertIn("notification-read-stopped-task-arns.json", capture_step)
        self.assertIn("notification-read-stopped-tasks.json", capture_step)
        self.assertIn("select-previous-ecs-tasks", capture_step)
        self.assertIn("aws ecs describe-services", capture_step)
        self.assertIn("consecutive_stable_snapshots", capture_step)
        self.assertIn("cmp -s", capture_step)
        self.assertIn("sleep 5", capture_step)
        self.assertIn("umask 077", capture_step)
        self.assertIn("notification-read-previous-task-arns.json", capture_step)
        self.assertIn("aws ecs list-tasks", evidence_step)
        self.assertIn("--desired-status RUNNING", evidence_step)
        self.assertIn("--desired-status STOPPED", evidence_step)
        self.assertNotIn("--desired-status PENDING", evidence_step)
        self.assertIn("aws ecs describe-tasks", evidence_step)
        self.assertIn("previous-task-arns.json", evidence_step)
        self.assertIn("previous-tasks.json", evidence_step)
        self.assertIn("refresh_previous_task_evidence", evidence_step)
        self.assertIn("refresh_replacement_evidence", evidence_step)
        self.assertGreaterEqual(evidence_step.count("refresh_replacement_evidence"), 2)
        self.assertIn("for attempt in $(seq 1 60)", evidence_step)
        self.assertIn("a previous ECS task has not reached STOPPED", evidence_step)
        self.assertIn("sleep 5", evidence_step)
        self.assertIn("sleep 5\n            refresh_replacement_evidence", evidence_step)
        self.assertIn(
            "Previous ECS tasks did not reach STOPPED within the 5-minute evidence budget",
            evidence_step,
        )
        self.assertIn("final-stopped-task-arns.json", evidence_step)
        self.assertIn("final-stopped-tasks.json", evidence_step)
        self.assertIn("aws elbv2 describe-target-health", evidence_step)
        self.assertIn("alternateTargetGroupArn", evidence_step)
        self.assertIn("aws elbv2 describe-rules", evidence_step)
        self.assertIn("select((.Weight // 1) > 0)", evidence_step)
        self.assertIn("Production listener has no active target group", evidence_step)
        self.assertIn("Production listener selected an unexpected target group", evidence_step)
        self.assertIn('grep -Fxq "$target_group_arn"', evidence_step)
        self.assertIn("trafficTargetGroupArns", evidence_step)
        self.assertIn("verify-production-ecs", evidence_step)
        self.assertIn("--expected-task-definition", evidence_step)

        cleanup_step = self.workflow.split(
            "- name: Cleanup previous ECS task identity evidence", 1
        )[1]
        self.assertIn("if: always()", cleanup_step)
        self.assertIn("notification-read-previous-task-arns.json", cleanup_step)

    def test_revision_gate_runs_from_the_trusted_workflow_revision(self):
        self.assertGreaterEqual(self.workflow.count("github.workflow_sha"), 2)
        self.assertGreaterEqual(
            self.workflow.count("$RUNNER_TEMP/notification-read-deployment-gate.py"),
            4,
        )
        self.assertNotIn(
            "python3 scripts/test-notification-read-deployment-gate.py verify-ancestor",
            self.workflow,
        )

    def test_backend_ci_runs_the_workflow_contract_test(self):
        self.assertIn("Verify production ECS CD workflow contract", self.backend_ci)
        self.assertIn("python3 scripts/test-production-ecs-cd-workflow.py", self.backend_ci)
        self.assertIn(
            "python3 scripts/test-notification-read-deployment-gate.py",
            self.backend_ci,
        )


if __name__ == "__main__":
    unittest.main()
