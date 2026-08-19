import os
import subprocess
import tempfile
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
            'case "${llm_enabled}" in', 1
        )[1].split("esac", 1)[0]
        self.assertIn("awk '!/^OPENAI_API_KEY=/'", disabled_branch)
        self.assertIn("return 0", disabled_branch)
        self.assertNotIn("aws ssm get-parameter", disabled_branch)

    def test_missing_llm_flag_defaults_to_disabled_before_synchronizing_openai(self):
        self.assertIn('llm_enabled="${llm_enabled:-false}"', self.script)
        self.assertNotIn('llm_enabled="${llm_enabled:-true}"', self.script)
        self.assertIn("false)", self.script)

    def test_enabled_llm_reads_the_openai_parameter(self):
        self.assertIn('aws ssm get-parameter', self.script)
        self.assertIn('OPENAI_API_KEY_PARAMETER_NAME', self.script)

    def test_compose_ignores_host_llm_environment_variables(self):
        self.assertIn("unset OPENAI_API_KEY MIRIYUM_STORE_SEARCH_LLM_ENABLED", self.script)
        self.assertIn('docker compose "$@"', self.script)

    def test_all_compose_calls_use_the_sanitized_environment(self):
        self.assertNotIn('local compose=(docker compose', self.script)
        self.assertNotIn('\n  docker compose --env-file', self.script)
        self.assertIn('local compose=(compose_command --env-file', self.script)
        self.assertIn('compose_command --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" pull', self.script)

    def test_compose_uses_disabled_llm_value_from_env_file_not_host_shell(self):
        output = self.run_compose_command(
            "MIRIYUM_STORE_SEARCH_LLM_ENABLED=false\n",
        )

        self.assertIn("host_openai_api_key=<unset>", output)
        self.assertIn("host_llm_enabled=<unset>", output)
        self.assertIn("MIRIYUM_STORE_SEARCH_LLM_ENABLED=false", output)
        self.assertNotIn("host-openai-key", output)

    def test_compose_uses_enabled_llm_value_from_env_file_not_host_shell(self):
        output = self.run_compose_command(
            "MIRIYUM_STORE_SEARCH_LLM_ENABLED=true\nOPENAI_API_KEY=current-openai-key\n",
        )

        self.assertIn("host_openai_api_key=<unset>", output)
        self.assertIn("host_llm_enabled=<unset>", output)
        self.assertIn("MIRIYUM_STORE_SEARCH_LLM_ENABLED=true", output)
        self.assertIn("OPENAI_API_KEY=current-openai-key", output)
        self.assertNotIn("host-openai-key", output)

    def run_compose_command(self, env_file_contents):
        with tempfile.TemporaryDirectory() as directory:
            temporary_directory = Path(directory)
            env_file = temporary_directory / ".env"
            fake_docker = temporary_directory / "docker"
            env_file.write_text(env_file_contents, encoding="utf-8")
            fake_docker.write_text(
                """#!/usr/bin/env bash
set -euo pipefail
env_file=""
while (($#)); do
  if [[ "$1" == "--env-file" ]]; then
    env_file="$2"
    shift 2
    continue
  fi
  shift
done
printf 'host_openai_api_key=%s\\n' "${OPENAI_API_KEY-<unset>}"
printf 'host_llm_enabled=%s\\n' "${MIRIYUM_STORE_SEARCH_LLM_ENABLED-<unset>}"
cat "$env_file"
""",
                encoding="utf-8",
            )
            fake_docker.chmod(0o755)

            environment = os.environ.copy()
            environment["PATH"] = f"{temporary_directory}{os.pathsep}{environment['PATH']}"
            environment["OPENAI_API_KEY"] = "host-openai-key"
            environment["MIRIYUM_STORE_SEARCH_LLM_ENABLED"] = "true"
            bash_executable = "bash"
            if os.name == "nt":
                bash_executable = str(Path(os.environ["ProgramFiles"]) / "Git" / "bin" / "bash.exe")
            result = subprocess.run(
                [
                    bash_executable,
                    "-c",
                    'source "$1"; compose_command --env-file "$2" -f ignored.yml config',
                    "--",
                    str(DEPLOY_SCRIPT),
                    str(env_file),
                ],
                check=False,
                capture_output=True,
                encoding="utf-8",
                env=environment,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            return result.stdout


if __name__ == "__main__":
    unittest.main()
