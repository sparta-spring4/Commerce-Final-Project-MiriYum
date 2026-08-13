import re
import subprocess
import sys
import unittest
from pathlib import Path


WORKFLOW = Path(".github/workflows/backend-cd.yml").read_text(encoding="utf-8")


class ShouldDeployBackendTest(unittest.TestCase):
    def assert_decision(self, paths, expected):
        result = subprocess.run(
            [sys.executable, "scripts/should-deploy-backend.py"],
            input="\n".join(paths) + ("\n" if paths else ""),
            text=True,
            capture_output=True,
            check=True,
        )
        self.assertEqual(expected, result.stdout.strip())

    def test_backend_change_deploys(self):
        self.assert_decision(["backend/src/main/App.java"], "true")

    def test_deploy_change_deploys(self):
        self.assert_decision(["deploy/docker-compose.prod.yml"], "true")

    def test_backend_cd_workflow_change_deploys(self):
        self.assert_decision([".github/workflows/backend-cd.yml"], "true")

    def test_docs_and_frontend_only_changes_skip(self):
        self.assert_decision(["docs/README.md", "frontend/src/App.tsx"], "false")

    def test_accumulated_backend_change_before_docs_change_still_deploys(self):
        self.assert_decision(
            ["backend/src/main/App.java", "docs/README.md"],
            "true",
        )

    def test_empty_changes_skip(self):
        self.assert_decision([], "false")

    def test_backend_marker_uses_selected_image_sha(self):
        self.assertIn("BACKEND_DEPLOYMENT_ENVIRONMENT: staging-backend", WORKFLOW)
        self.assertIn("deployments: write", WORKFLOW)
        self.assertIn("Record backend deployment marker", WORKFLOW)
        marker = WORKFLOW.split("- name: Record backend deployment marker", 1)[1].split(
            "- name: Upload deployment files with SSM", 1
        )[0]
        payload = re.search(
            r"'\{ref: \$ref, environment: \$environment, description: \(.+?\), "
            r"auto_merge: false, required_contexts: \[\]\}'",
            marker,
            re.DOTALL,
        )
        self.assertIsNotNone(payload)
        payload_keys = re.findall(r"([a-z_]+):", payload.group(0))
        self.assertEqual(
            ["ref", "environment", "description", "auto_merge", "required_contexts"],
            payload_keys,
        )
        self.assertNotIn("sha: $sha", payload.group(0))
        self.assertIn('--arg ref "$IMAGE_TAG"', marker)
        self.assertIn("if .sha != $image_tag then", marker)
        self.assertIn("Deployment response SHA does not match selected image tag", marker)
        self.assertIn(
            '--field environment="$BACKEND_DEPLOYMENT_ENVIRONMENT"',
            WORKFLOW,
        )

    def test_backend_marker_records_success_and_failure(self):
        self.assertIn("Mark backend deployment successful", WORKFLOW)
        self.assertIn("--field state=success", WORKFLOW)
        self.assertIn("Mark backend deployment failed", WORKFLOW)
        self.assertIn("--field state=failure", WORKFLOW)

    def test_source_lookup_uses_backend_marker_environment(self):
        self.assertIn(
            "deployments?environment=$BACKEND_DEPLOYMENT_ENVIRONMENT&per_page=100",
            WORKFLOW,
        )

    def test_backend_ci_verifies_production_task_definition_secret_contract(self):
        backend_ci = Path(".github/workflows/backend-ci.yml").read_text(encoding="utf-8")

        self.assertIn("Verify production task definition secret contract", backend_ci)
        self.assertIn("scripts/test-verify-production-task-definition.py", backend_ci)
        self.assertIn("scripts/verify-production-task-definition.py", backend_ci)


if __name__ == "__main__":
    unittest.main()
