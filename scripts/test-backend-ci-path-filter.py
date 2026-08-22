import subprocess
import sys
import tempfile
import unittest
from contextlib import contextmanager
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
        self.assert_scope(["deploy/deploy.sh"], "full")

    def test_openapi_changes_run_unit_tests_without_integration_shards(self):
        self.assert_scope(["docs/specs/auth-account/openapi.yaml"], "unit")
        self.assert_scope(["docs/specs/consumer-openapi.yaml"], "unit")

    def test_document_frontend_k6_and_deploy_workflow_changes_skip_heavy_tests(self):
        self.assert_scope(["docs/deployment/production-ecs-incident-runbook.md"], "contract")
        self.assert_scope(["frontend/src/app/App.tsx"], "contract")
        self.assert_scope(["performance/k6/config.js"], "contract")
        self.assert_scope(["deploy/docker-compose.prod.yml"], "unit")
        self.assert_scope([".github/workflows/backend-cd.yml"], "contract")
        self.assert_scope([".github/workflows/staging-load-test-control.yml"], "contract")
        self.assert_scope([".github/workflows/staging-valkey-control.yml"], "contract")
        self.assert_scope([".github/workflows/frontend-ci.yml"], "contract")
        self.assert_scope([".github/workflows/k6-contract.yml"], "contract")
        self.assert_scope(["scripts/test-staging-load-test-control-workflow.py"], "contract")
        self.assert_scope(["scripts/staging-valkey-control.sh"], "contract")
        self.assert_scope(["scripts/test-staging-valkey-control.py"], "contract")

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
        self.assertIn('git diff --name-only --no-renames "$BASE_SHA...$HEAD_SHA"', workflow)

    def test_integration_shards_publish_duration_reports_on_success_and_failure(self):
        workflow = WORKFLOW_PATH.read_text(encoding="utf-8")

        self.assertIn("Generate integration test duration report", workflow)
        self.assertIn("backend-ci-test-duration-report.py", workflow)
        self.assertIn("if: always()", workflow)
        self.assertIn("backend-integration-test-${{ matrix.shard }}-duration", workflow)
        self.assertIn("backend/build/test-results/${{ matrix.report_directory }}", workflow)
        self.assertIn("backend/test-duration-${{ matrix.shard }}.md", workflow)

    def test_merge_base_diff_ignores_backend_changes_added_only_to_base(self):
        with temporary_git_repository() as repository:
            write_file(repository, "frontend/src/App.tsx", "base")
            run_git(repository, "add", ".")
            run_git(repository, "commit", "-qm", "base")
            base = run_git(repository, "rev-parse", "HEAD").strip()

            run_git(repository, "switch", "-qc", "feature")
            write_file(repository, "frontend/src/App.tsx", "feature")
            run_git(repository, "commit", "-am", "feature change")
            head = run_git(repository, "rev-parse", "HEAD").strip()

            run_git(repository, "switch", "-q", "-c", "dev", base)
            write_file(repository, "backend/src/main/java/Example.java", "base only")
            run_git(repository, "add", ".")
            run_git(repository, "commit", "-qm", "backend on base")

            paths = changed_paths(repository, base, head)

        self.assertEqual(["frontend/src/App.tsx"], paths)
        self.assert_scope(paths, "contract")

    def test_rename_from_backend_to_safe_path_keeps_deleted_backend_path(self):
        with temporary_git_repository() as repository:
            write_file(repository, "backend/Foo.java", "class Foo {}")
            write_file(repository, "docs/.gitkeep", "")
            run_git(repository, "add", ".")
            run_git(repository, "commit", "-qm", "base")
            base = run_git(repository, "rev-parse", "HEAD").strip()

            run_git(repository, "mv", "backend/Foo.java", "docs/Foo.java")
            run_git(repository, "commit", "-qm", "move backend source")
            head = run_git(repository, "rev-parse", "HEAD").strip()

            paths = changed_paths(repository, base, head)

        self.assertEqual(["backend/Foo.java", "docs/Foo.java"], paths)
        self.assert_scope(paths, "full")


@contextmanager
def temporary_git_repository():
    with tempfile.TemporaryDirectory() as temporary_directory:
        repository = Path(temporary_directory)
        run_git(repository, "init", "-q")
        run_git(repository, "config", "user.name", "Backend CI path filter test")
        run_git(repository, "config", "user.email", "backend-ci-path-filter@example.com")
        yield repository


def write_file(repository, path, content):
    target = repository / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding="utf-8")


def changed_paths(repository, base, head):
    output = run_git(repository, "diff", "--name-only", "--no-renames", f"{base}...{head}")
    return [path for path in output.splitlines() if path]


def run_git(repository, *arguments):
    return subprocess.run(
        ["git", *arguments],
        cwd=repository,
        text=True,
        capture_output=True,
        check=True,
    ).stdout


if __name__ == "__main__":
    unittest.main()
