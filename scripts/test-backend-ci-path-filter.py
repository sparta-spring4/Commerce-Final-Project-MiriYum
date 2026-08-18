import subprocess
import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
FILTER_PATH = ROOT / "scripts" / "backend-ci-path-filter.py"
WORKFLOW_PATH = ROOT / ".github" / "workflows" / "backend-ci.yml"


class BackendCiPathFilterTest(unittest.TestCase):
    def assert_scope(self, paths, expected):
        result = subprocess.run(
            [sys.executable, str(FILTER_PATH)],
            input="\n".join(paths) + ("\n" if paths else ""),
            text=True,
            capture_output=True,
            check=True,
        )
        self.assertEqual(expected, result.stdout.strip())

    def test_backend_code_and_ci_changes_run_full_test_suite(self):
        self.assert_scope(["backend/src/main/java/com/miriyum/MiriyumApplication.java"], "full")
        self.assert_scope(["backend/src/main/resources/db/migration/V57__example.sql"], "full")
        self.assert_scope([".github/workflows/backend-ci.yml"], "full")

    def test_openapi_changes_run_unit_tests_without_integration_shards(self):
        self.assert_scope(["docs/specs/auth-account/openapi.yaml"], "unit")
        self.assert_scope(["docs/specs/consumer-openapi.yaml"], "unit")

    def test_document_frontend_k6_and_deploy_workflow_changes_skip_heavy_tests(self):
        self.assert_scope(["docs/deployment/production-ecs-incident-runbook.md"], "contract")
        self.assert_scope(["frontend/src/app/App.tsx"], "contract")
        self.assert_scope(["performance/k6/config.js"], "contract")
        self.assert_scope(["deploy/docker-compose.prod.yml"], "contract")
        self.assert_scope([".github/workflows/backend-cd.yml"], "contract")
        self.assert_scope([".github/workflows/frontend-ci.yml"], "contract")
        self.assert_scope([".github/workflows/k6-contract.yml"], "contract")

    def test_unknown_or_empty_path_fails_safe_to_full_tests(self):
        self.assert_scope(["infra/unknown.yml"], "full")
        self.assert_scope([], "full")

    def test_backend_ci_keeps_required_aggregate_and_conditions_test_jobs(self):
        workflow = WORKFLOW_PATH.read_text(encoding="utf-8")
        self.assertIn("changes:", workflow)
        self.assertIn("test_scope", workflow)
        self.assertIn("needs: [changes, unit-test, integration-test]", workflow)
        self.assertIn('test "$UNIT_TEST_RESULT" = "skipped"', workflow)
        self.assertIn('test "$INTEGRATION_TEST_RESULT" = "skipped"', workflow)


if __name__ == "__main__":
    unittest.main()
