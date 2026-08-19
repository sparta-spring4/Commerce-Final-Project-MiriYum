import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
APPLICATION_CONFIG = ROOT / "backend" / "src" / "main" / "resources" / "application.yml"
CONTRACT = ROOT / "deploy" / "backend-runtime-environment.txt"
COMPOSE = ROOT / "deploy" / "docker-compose.prod.yml"
DEPLOY_SCRIPT = ROOT / "deploy" / "deploy.sh"


class BackendRuntimeEnvironmentContractTest(unittest.TestCase):
    def test_application_environment_names_match_the_contract(self):
        application_names = set(re.findall(
            r"\$\{((?:MIRIYUM_[A-Z0-9_]+)|OPENAI_API_KEY)",
            APPLICATION_CONFIG.read_text(encoding="utf-8"),
        ))
        contract_names = {
            line.strip()
            for line in CONTRACT.read_text(encoding="utf-8").splitlines()
            if line.strip() and not line.startswith("#")
        }
        self.assertEqual(application_names, contract_names)

    def test_staging_compose_reads_the_generated_allow_list_file(self):
        compose = COMPOSE.read_text(encoding="utf-8")
        self.assertIn("env_file:", compose)
        self.assertIn("${BACKEND_RUNTIME_ENV_FILE:-./.env.example}", compose)

    def test_deploy_script_generates_a_restricted_backend_environment_file(self):
        deploy_script = DEPLOY_SCRIPT.read_text(encoding="utf-8")
        self.assertIn("RUNTIME_ENVIRONMENT_CONTRACT_FILE", deploy_script)
        self.assertIn("sync_backend_runtime_environment", deploy_script)
        self.assertIn("grep -m1", deploy_script)
        self.assertIn("export BACKEND_RUNTIME_ENV_FILE", deploy_script)


if __name__ == "__main__":
    unittest.main()
