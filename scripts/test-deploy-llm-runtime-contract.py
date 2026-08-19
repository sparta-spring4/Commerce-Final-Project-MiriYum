import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DEPLOY_SCRIPT = ROOT / "deploy" / "deploy.sh"


class DeployLlmRuntimeContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.script = DEPLOY_SCRIPT.read_text(encoding="utf-8")

    def test_explicitly_disabled_llm_skips_ssm_and_removes_stale_key(self):
        disabled_branch = self.script.split(
            'if [[ "${llm_enabled:-true}" == "false" ]]; then', 1
        )[1].split("fi", 1)[0]
        self.assertIn("awk '!/^OPENAI_API_KEY=/'", disabled_branch)
        self.assertIn("return 0", disabled_branch)
        self.assertNotIn("aws ssm get-parameter", disabled_branch)

    def test_enabled_llm_reads_the_openai_parameter(self):
        self.assertIn('aws ssm get-parameter', self.script)
        self.assertIn('OPENAI_API_KEY_PARAMETER_NAME', self.script)


if __name__ == "__main__":
    unittest.main()
