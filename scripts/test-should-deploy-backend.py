import subprocess
import sys
import unittest


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


if __name__ == "__main__":
    unittest.main()
