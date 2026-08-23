import os
import shutil
import subprocess
import tempfile
import time
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT_PATH = ROOT / "scripts" / "staging-valkey-control.sh"
BASH = shutil.which("bash")
if BASH is None or Path(BASH).name.lower() == "bash.exe" and "system32" in BASH.lower():
    BASH = r"C:\Program Files\Git\bin\bash.exe"


class StagingValkeyControlTest(unittest.TestCase):
    def run_control(
        self,
        action,
        *,
        fail_sleep=False,
        signal_sleep=False,
        fail_docker_contains="",
        backend_container_ids="staging-backend-container",
        frontend_container_ids="staging-frontend-container",
        backend_running=True,
        frontend_running=True,
        backend_oneoff_ids="",
        frontend_oneoff_ids="",
        execute_at_epoch=None,
    ):
        with tempfile.TemporaryDirectory() as directory:
            temporary_path = Path(directory)
            command_log = temporary_path / "commands.log"
            fake_bin = temporary_path / "bin"
            fake_bin.mkdir()

            docker = fake_bin / "docker"
            docker.write_text(
                """#!/usr/bin/env bash
set -Eeuo pipefail
printf 'docker %s\\n' \"$*\" >> \"$FAKE_COMMAND_LOG\"
if [[ -n \"${FAIL_DOCKER_CONTAINS:-}\" && \"$*\" == *\"$FAIL_DOCKER_CONTAINS\"* ]]; then
  printf 'raw-sensitive-docker-error\\n' >&2
  exit 23
fi
if [[ \"$*\" == *\"label=com.docker.compose.service=backend\"* ]]; then
  ids=''
  if [[ \"${BACKEND_RUNNING:-false}\" == \"true\" || \"$*\" == *\"ps -aq \"* ]]; then
    ids=\"${BACKEND_CONTAINER_IDS:-}\"
  fi
  if [[ \"$*\" != *\"label=com.docker.compose.oneoff=False\"* && -n \"${BACKEND_ONEOFF_IDS:-}\" ]]; then
    [[ -z \"$ids\" ]] || ids+=\"\\n\"
    ids+=\"${BACKEND_ONEOFF_IDS}\"
  fi
  printf '%b' \"$ids\"
elif [[ \"$*\" == *\"label=com.docker.compose.service=frontend\"* ]]; then
  ids=''
  if [[ \"${FRONTEND_RUNNING:-false}\" == \"true\" || \"$*\" == *\"ps -aq \"* ]]; then
    ids=\"${FRONTEND_CONTAINER_IDS:-}\"
  fi
  if [[ \"$*\" != *\"label=com.docker.compose.oneoff=False\"* && -n \"${FRONTEND_ONEOFF_IDS:-}\" ]]; then
    [[ -z \"$ids\" ]] || ids+=\"\\n\"
    ids+=\"${FRONTEND_ONEOFF_IDS}\"
  fi
  printf '%b' \"$ids\"
elif [[ \"$*\" == *\"{{.Config.Image}} staging-backend-container\"* ]]; then
  printf 'registry.invalid/backend:private-runtime-image\\n'
elif [[ \"$*\" == *\"{{.Config.Image}} staging-backend-oneoff\"* ]]; then
  printf 'registry.invalid/backend:private-runtime-image\\n'
elif [[ \"$*\" == *\"{{.Config.Image}} staging-frontend-container\"* ]]; then
  printf 'registry.invalid/frontend:private-runtime-image\\n'
elif [[ \"$*\" == *\"{{.Config.Image}} staging-frontend-oneoff\"* ]]; then
  printf 'registry.invalid/frontend:private-runtime-image\\n'
elif [[ \"$1\" == \"compose\" && \"${REQUIRE_RUNTIME_IMAGE_BINDINGS:-false}\" == \"true\" ]]; then
  if [[ \"${BACKEND_IMAGE:-}\" != \"registry.invalid/backend:private-runtime-image\" || \\
        \"${FRONTEND_IMAGE:-}\" != \"registry.invalid/frontend:private-runtime-image\" ]]; then
    printf 'raw-sensitive-compose-image-binding-error\\n' >&2
    exit 31
  fi
  if [[ \"$*\" == *\" ps -q valkey\" ]]; then
    printf 'staging-valkey-container\\n'
  fi
elif [[ \"$1\" == \"inspect\" ]]; then
  printf 'healthy\\n'
elif [[ \"$*\" == *\" ps -q valkey\" ]]; then
  printf 'staging-valkey-container\\n'
fi
""",
                encoding="utf-8",
                newline="\n",
            )
            docker.chmod(0o755)

            sleep = fake_bin / "sleep"
            sleep.write_text(
                """#!/usr/bin/env bash
set -Eeuo pipefail
printf 'sleep %s\\n' \"$*\" >> \"$FAKE_COMMAND_LOG\"
if [[ \"${FAIL_SLEEP:-false}\" == \"true\" && \"$1\" == \"10\" ]]; then
  exit 7
fi
if [[ \"${SIGNAL_SLEEP:-false}\" == \"true\" && \"$1\" == \"10\" ]]; then
  kill -TERM \"$PPID\"
  /usr/bin/sleep 1
fi
""",
                encoding="utf-8",
                newline="\n",
            )
            sleep.chmod(0o755)

            if execute_at_epoch is None:
                execute_at_epoch = str(int(time.time()) + 30) if action == "interrupt" else ""

            command = (
                'fake_bin="$1"; '
                'if command -v cygpath >/dev/null 2>&1; then '
                'fake_bin=$(cygpath -u "$fake_bin"); '
                'fi; '
                'PATH="$fake_bin:$PATH" VALKEY_CONTROL_ACTION="$2" '
                'VALKEY_CONTROL_EXECUTE_AT_EPOCH="$3" bash "$4"'
            )
            environment = os.environ.copy()
            environment.update(
                {
                    "FAKE_COMMAND_LOG": command_log.as_posix(),
                    "FAIL_SLEEP": "true" if fail_sleep else "false",
                    "SIGNAL_SLEEP": "true" if signal_sleep else "false",
                    "FAIL_DOCKER_CONTAINS": fail_docker_contains,
                    "REQUIRE_RUNTIME_IMAGE_BINDINGS": "true",
                    "BACKEND_CONTAINER_IDS": backend_container_ids,
                    "FRONTEND_CONTAINER_IDS": frontend_container_ids,
                    "BACKEND_RUNNING": "true" if backend_running else "false",
                    "FRONTEND_RUNNING": "true" if frontend_running else "false",
                    "BACKEND_ONEOFF_IDS": backend_oneoff_ids,
                    "FRONTEND_ONEOFF_IDS": frontend_oneoff_ids,
                }
            )
            result = subprocess.run(
                [
                    BASH,
                    "-c",
                    command,
                    "staging-valkey-test",
                    fake_bin.as_posix(),
                    action,
                    execute_at_epoch,
                    SCRIPT_PATH.as_posix(),
                ],
                capture_output=True,
                text=True,
                check=False,
                env=environment,
            )
            commands = (
                command_log.read_text(encoding="utf-8").splitlines()
                if command_log.exists()
                else []
            )
            return result, commands

    def test_interrupt_stops_for_ten_seconds_then_recovers_to_healthy(self):
        result, commands = self.run_control("interrupt")

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        stop = commands.index(
            "docker compose --env-file /opt/miriyum/.env -f "
            "/opt/miriyum/docker-compose.prod.yml stop valkey"
        )
        wait = commands.index("sleep 10")
        start = commands.index(
            "docker compose --env-file /opt/miriyum/.env -f "
            "/opt/miriyum/docker-compose.prod.yml up -d --no-deps valkey"
        )
        healthy = max(index for index, command in enumerate(commands) if command ==
            "docker inspect --format {{.State.Health.Status}} staging-valkey-container"
        )
        self.assertLess(stop, wait)
        self.assertLess(wait, start)
        self.assertLess(start, healthy)

    def test_recover_starts_and_checks_health_without_stopping(self):
        result, commands = self.run_control("recover")

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertNotIn("sleep 10", commands)
        self.assertFalse(any(" stop valkey" in command for command in commands))
        self.assertTrue(any(" up -d --no-deps valkey" in command for command in commands))
        self.assertTrue(any(command.startswith("docker inspect") for command in commands))

    def test_interrupt_waits_for_a_bounded_future_epoch_before_stopping(self):
        execute_at = int(time.time()) + 30
        result, commands = self.run_control("interrupt", execute_at_epoch=str(execute_at))

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        scheduled_wait = next(
            index for index, command in enumerate(commands)
            if command.startswith("sleep ") and command != "sleep 10"
        )
        stop = next(index for index, command in enumerate(commands) if " stop valkey" in command)
        self.assertLess(scheduled_wait, stop)

    def test_interrupt_rejects_invalid_or_unbounded_epoch_before_docker_access(self):
        for execute_at in ("not-an-epoch", str(int(time.time()) - 1), str(int(time.time()) + 120)):
            with self.subTest(execute_at=execute_at):
                result, commands = self.run_control("interrupt", execute_at_epoch=execute_at)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("reason=invalid-scheduled-epoch", result.stdout + result.stderr)
                self.assertEqual([], commands)

    def test_recover_rejects_a_scheduled_epoch_without_docker_access(self):
        result, commands = self.run_control(
            "recover",
            execute_at_epoch=str(int(time.time()) + 30),
        )

        self.assertNotEqual(0, result.returncode)
        self.assertIn("reason=unexpected-scheduled-epoch", result.stdout + result.stderr)
        self.assertEqual([], commands)

    def test_interrupt_failure_still_recovers_and_preserves_failure(self):
        result, commands = self.run_control("interrupt", fail_sleep=True)

        self.assertNotEqual(0, result.returncode)
        self.assertTrue(any(" stop valkey" in command for command in commands))
        self.assertIn("sleep 10", commands)
        self.assertTrue(any(" up -d --no-deps valkey" in command for command in commands))
        self.assertTrue(any(command.startswith("docker inspect") for command in commands))

    def test_interrupt_signal_still_recovers_and_returns_signal_status(self):
        result, commands = self.run_control("interrupt", signal_sleep=True)

        self.assertEqual(143, result.returncode, result.stdout + result.stderr)
        self.assertTrue(any(" stop valkey" in command for command in commands))
        self.assertIn("sleep 10", commands)
        self.assertTrue(any(" up -d --no-deps valkey" in command for command in commands))
        self.assertTrue(any(command.startswith("docker inspect") for command in commands))

    def test_arbitrary_action_is_rejected_without_docker_access(self):
        result, commands = self.run_control("stop")

        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], commands)

    def assert_safe_failure(self, result, *, phase, reason, exit_code=23):
        combined = result.stdout + result.stderr
        self.assertNotEqual(0, result.returncode)
        self.assertIn(
            "event=staging_valkey_control_failed "
            f"action={result.args[-3]} phase={phase} reason={reason} "
            f"exit_code={exit_code}",
            combined,
        )
        self.assertNotIn("raw-sensitive-docker-error", combined)
        self.assertNotIn("raw-sensitive-compose-image-binding-error", combined)

    def test_recover_binds_current_runtime_images_without_logging_them(self):
        result, commands = self.run_control("recover")

        combined = result.stdout + result.stderr
        self.assertEqual(0, result.returncode, combined)
        self.assertNotIn("private-runtime-image", combined)
        self.assertNotIn("private-runtime-image", "\n".join(commands))

    def test_recover_rejects_missing_runtime_image_before_valkey_start(self):
        result, commands = self.run_control(
            "recover",
            backend_container_ids="",
        )

        self.assert_safe_failure(
            result,
            phase="preflight",
            reason="runtime-image-binding-failed",
            exit_code=1,
        )
        self.assertFalse(any("up -d --no-deps valkey" in command for command in commands))

    def test_interrupt_rejects_ambiguous_runtime_image_before_valkey_stop(self):
        result, commands = self.run_control(
            "interrupt",
            frontend_container_ids="staging-frontend-container\nold-frontend-container",
        )

        self.assert_safe_failure(
            result,
            phase="preflight",
            reason="runtime-image-binding-failed",
            exit_code=1,
        )
        self.assertFalse(any("stop valkey" in command for command in commands))

    def test_recover_rejects_stopped_runtime_service_before_valkey_start(self):
        for service in ("backend", "frontend"):
            with self.subTest(service=service):
                result, commands = self.run_control(
                    "recover",
                    backend_running=service != "backend",
                    frontend_running=service != "frontend",
                )

                self.assert_safe_failure(
                    result,
                    phase="preflight",
                    reason="runtime-image-binding-failed",
                    exit_code=1,
                )
                self.assertFalse(
                    any("up -d --no-deps valkey" in command for command in commands)
                )

    def test_recover_rejects_oneoff_without_running_service_before_valkey_start(self):
        for service in ("backend", "frontend"):
            with self.subTest(service=service):
                result, commands = self.run_control(
                    "recover",
                    backend_running=service != "backend",
                    frontend_running=service != "frontend",
                    backend_oneoff_ids=(
                        "staging-backend-oneoff" if service == "backend" else ""
                    ),
                    frontend_oneoff_ids=(
                        "staging-frontend-oneoff" if service == "frontend" else ""
                    ),
                )

                self.assert_safe_failure(
                    result,
                    phase="preflight",
                    reason="runtime-image-binding-failed",
                    exit_code=1,
                )
                self.assertFalse(
                    any("up -d --no-deps valkey" in command for command in commands)
                )

    def test_recover_reports_safe_start_failure_without_raw_docker_error(self):
        result, _ = self.run_control(
            "recover",
            fail_docker_contains="up -d --no-deps valkey",
        )

        self.assert_safe_failure(result, phase="start", reason="compose-up-failed")

    def test_recover_reports_safe_health_failure_without_raw_docker_error(self):
        result, _ = self.run_control(
            "recover",
            fail_docker_contains="ps -q valkey",
        )

        self.assert_safe_failure(
            result,
            phase="health-check",
            reason="compose-ps-failed",
        )

    def test_interrupt_reports_safe_stop_failure_without_raw_docker_error(self):
        result, _ = self.run_control(
            "interrupt",
            fail_docker_contains="stop valkey",
        )

        self.assert_safe_failure(result, phase="stop", reason="compose-stop-failed")

    def test_recover_reports_safe_compose_preflight_failure(self):
        result, commands = self.run_control(
            "recover",
            fail_docker_contains="config --quiet",
        )

        self.assert_safe_failure(
            result,
            phase="preflight",
            reason="compose-config-invalid",
        )
        self.assertFalse(any("up -d --no-deps valkey" in command for command in commands))


if __name__ == "__main__":
    unittest.main()
