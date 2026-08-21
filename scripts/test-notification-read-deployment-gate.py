import argparse
import copy
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


FULL_GIT_SHA = re.compile(r"[0-9a-f]{40}")
MINIMUM_COMPATIBLE_WRITER_SHA = "515531e122ebbce13d8eead4a3ff15a94c25e0b3"


class DeploymentGateError(RuntimeError):
    pass


def verify_compatible_revision(repository, minimum_sha, candidate_sha):
    for label, revision in (
        ("minimum compatible revision", minimum_sha),
        ("candidate revision", candidate_sha),
    ):
        if FULL_GIT_SHA.fullmatch(revision) is None:
            raise DeploymentGateError(f"{label} must be a full lowercase Git SHA")

        result = subprocess.run(
            ["git", "cat-file", "-e", f"{revision}^{{commit}}"],
            cwd=repository,
            capture_output=True,
            text=True,
        )
        if result.returncode != 0:
            raise DeploymentGateError(f"{label} is not available in the checkout")

    result = subprocess.run(
        ["git", "merge-base", "--is-ancestor", minimum_sha, candidate_sha],
        cwd=repository,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        raise DeploymentGateError(
            "candidate revision is older than or outside the minimum compatible writer history"
        )


def verify_production_ecs_evidence(
    service,
    tasks,
    previous_task_arns,
    previous_tasks,
    target_health_by_arn,
    expected_task_definition,
):
    if not all(
        isinstance(value, dict)
        for value in (service, tasks, previous_tasks, target_health_by_arn)
    ):
        raise DeploymentGateError("deployment evidence shape is invalid")
    if not expected_task_definition:
        raise DeploymentGateError("expected task definition evidence is missing")

    primary_deployments = [
        deployment
        for deployment in service.get("deployments", [])
        if deployment.get("status") == "PRIMARY"
    ]
    if len(primary_deployments) != 1:
        raise DeploymentGateError("exactly one primary ECS deployment is required")

    primary_deployment = primary_deployments[0]
    if (
        service.get("taskDefinition") != expected_task_definition
        or primary_deployment.get("taskDefinition") != expected_task_definition
        or primary_deployment.get("rolloutState") != "COMPLETED"
    ):
        raise DeploymentGateError("expected ECS task revision is not fully deployed")

    desired_count = service.get("desiredCount")
    if (
        not isinstance(desired_count, int)
        or desired_count < 1
        or service.get("runningCount") != desired_count
        or service.get("pendingCount") != 0
    ):
        raise DeploymentGateError("ECS replacement task counts are incomplete")

    if tasks.get("failures"):
        raise DeploymentGateError("ECS task evidence contains lookup failures")

    current_tasks = tasks.get("tasks", [])
    if len(current_tasks) != desired_count or any(
        task.get("taskDefinitionArn") != expected_task_definition
        or task.get("lastStatus") != "RUNNING"
        or task.get("desiredStatus") != "RUNNING"
        for task in current_tasks
    ):
        raise DeploymentGateError("current ECS task evidence does not match desired count")

    if (
        not isinstance(previous_task_arns, list)
        or not previous_task_arns
        or any(
            not isinstance(task_arn, str) or not task_arn
            for task_arn in previous_task_arns
        )
        or len(set(previous_task_arns)) != len(previous_task_arns)
    ):
        raise DeploymentGateError("previous ECS task identity evidence is invalid")
    if previous_tasks.get("failures"):
        raise DeploymentGateError("previous ECS task evidence contains lookup failures")

    described_previous_tasks = previous_tasks.get("tasks", [])
    described_previous_task_arns = {
        task.get("taskArn") for task in described_previous_tasks if task.get("taskArn")
    }
    if (
        described_previous_task_arns != set(previous_task_arns)
        or len(described_previous_tasks) != len(previous_task_arns)
    ):
        raise DeploymentGateError("previous ECS task lifecycle evidence is incomplete")
    if any(
        task.get("lastStatus") != "STOPPED"
        or task.get("desiredStatus") != "STOPPED"
        for task in described_previous_tasks
    ):
        raise DeploymentGateError("a previous ECS task has not reached STOPPED")

    target_group_arns = {
        load_balancer.get("targetGroupArn")
        for load_balancer in service.get("loadBalancers", [])
        if load_balancer.get("targetGroupArn")
    }
    if not target_group_arns:
        raise DeploymentGateError("ECS target group evidence is missing")

    for target_group_arn in target_group_arns:
        target_health = target_health_by_arn.get(target_group_arn)
        if target_health is None:
            raise DeploymentGateError("ECS target group health evidence is missing")

        descriptions = target_health.get("TargetHealthDescriptions", [])
        if len(descriptions) != desired_count or any(
            description.get("TargetHealth", {}).get("State") != "healthy"
            for description in descriptions
        ):
            raise DeploymentGateError(
                "ECS target replacement has not completed deregistration"
            )


def run_cli(arguments):
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)
    verify_parser = subparsers.add_parser("verify-ancestor")
    verify_parser.add_argument("--repository", default=".")
    verify_parser.add_argument(
        "--minimum-sha",
        default=MINIMUM_COMPATIBLE_WRITER_SHA,
    )
    verify_parser.add_argument("--candidate-sha", required=True)
    production_parser = subparsers.add_parser("verify-production-ecs")
    production_parser.add_argument("--evidence-json", required=True)
    production_parser.add_argument("--expected-task-definition", required=True)
    options = parser.parse_args(arguments)

    try:
        if options.command == "verify-ancestor":
            verify_compatible_revision(
                Path(options.repository),
                options.minimum_sha,
                options.candidate_sha,
            )
        else:
            evidence = json.loads(
                Path(options.evidence_json).read_text(encoding="utf-8")
            )
            if not isinstance(evidence, dict):
                raise DeploymentGateError("deployment evidence shape is invalid")
            verify_production_ecs_evidence(
                evidence.get("service", {}),
                evidence.get("tasks", {}),
                evidence.get("previousTaskArns", []),
                evidence.get("previousTasks", {}),
                evidence.get("targetHealthByArn", {}),
                options.expected_task_definition,
            )
    except (DeploymentGateError, OSError, json.JSONDecodeError) as error:
        print(f"Notification read deployment gate failed: {error}", file=sys.stderr)
        return 1

    if options.command == "verify-ancestor":
        print(
            "Notification read minimum compatible writer revision verified: "
            f"{options.candidate_sha}"
        )
    else:
        print("Notification read production ECS replacement evidence verified.")
    return 0


class RevisionFloorTest(unittest.TestCase):
    def setUp(self):
        self.temp_directory = tempfile.TemporaryDirectory()
        self.repository = Path(self.temp_directory.name)
        self._git("init", "--initial-branch=main")
        self._git("config", "user.email", "deployment-gate@example.invalid")
        self._git("config", "user.name", "Deployment Gate Test")

        self.base_sha = self._commit("base")
        self.floor_sha = self._commit("compatible-writer")
        self.descendant_sha = self._commit("forward-compatible")

        self._git("checkout", "-b", "legacy", self.base_sha)
        self.legacy_branch_sha = self._commit("legacy-branch")
        self._git("checkout", "main")

    def tearDown(self):
        self.temp_directory.cleanup()

    def _git(self, *arguments):
        return subprocess.run(
            ["git", *arguments],
            cwd=self.repository,
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()

    def _commit(self, content):
        marker = self.repository / "marker.txt"
        marker.write_text(content, encoding="utf-8")
        self._git("add", "marker.txt")
        self._git("commit", "-m", content)
        return self._git("rev-parse", "HEAD")

    def _run_cli(self, candidate_sha):
        return subprocess.run(
            [
                sys.executable,
                str(Path(__file__).resolve()),
                "verify-ancestor",
                "--repository",
                str(self.repository),
                "--minimum-sha",
                self.floor_sha,
                "--candidate-sha",
                candidate_sha,
            ],
            capture_output=True,
            text=True,
        )

    def test_descendant_revision_is_accepted(self):
        verify_compatible_revision(
            self.repository,
            self.floor_sha,
            self.descendant_sha,
        )

    def test_revision_older_than_floor_is_rejected(self):
        with self.assertRaises(DeploymentGateError):
            verify_compatible_revision(
                self.repository,
                self.floor_sha,
                self.base_sha,
            )

    def test_revision_outside_floor_history_is_rejected(self):
        with self.assertRaises(DeploymentGateError):
            verify_compatible_revision(
                self.repository,
                self.floor_sha,
                self.legacy_branch_sha,
            )

    def test_malformed_revision_is_rejected_before_git_lookup(self):
        with self.assertRaises(DeploymentGateError):
            verify_compatible_revision(
                self.repository,
                self.floor_sha,
                "not-a-full-git-sha",
            )

    def test_cli_accepts_forward_compatible_revision(self):
        result = self._run_cli(self.descendant_sha)

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("minimum compatible writer revision verified", result.stdout)

    def test_cli_fails_closed_for_legacy_revision(self):
        result = self._run_cli(self.legacy_branch_sha)

        self.assertEqual(1, result.returncode)
        self.assertIn(
            "candidate revision is older than or outside",
            result.stderr,
        )


class ProductionEcsEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.expected_task_definition = (
            "arn:aws:ecs:ap-northeast-2:123456789012:task-definition/miriyum:68"
        )
        self.previous_task_definition = (
            "arn:aws:ecs:ap-northeast-2:123456789012:task-definition/miriyum:67"
        )
        self.target_group_arn = (
            "arn:aws:elasticloadbalancing:ap-northeast-2:123456789012:"
            "targetgroup/miriyum/abcdef"
        )
        self.service = {
            "taskDefinition": self.expected_task_definition,
            "desiredCount": 2,
            "runningCount": 2,
            "pendingCount": 0,
            "deployments": [
                {
                    "status": "PRIMARY",
                    "taskDefinition": self.expected_task_definition,
                    "rolloutState": "COMPLETED",
                }
            ],
            "loadBalancers": [{"targetGroupArn": self.target_group_arn}],
        }
        self.tasks = {
            "tasks": [
                {
                    "taskArn": "arn:aws:ecs:task/current-1",
                    "taskDefinitionArn": self.expected_task_definition,
                    "lastStatus": "RUNNING",
                    "desiredStatus": "RUNNING",
                },
                {
                    "taskArn": "arn:aws:ecs:task/current-2",
                    "taskDefinitionArn": self.expected_task_definition,
                    "lastStatus": "RUNNING",
                    "desiredStatus": "RUNNING",
                },
            ],
            "failures": [],
        }
        self.previous_task_arns = [
            "arn:aws:ecs:task/previous-1",
            "arn:aws:ecs:task/previous-2",
        ]
        self.previous_tasks = {
            "tasks": [
                {
                    "taskArn": self.previous_task_arns[0],
                    "taskDefinitionArn": self.previous_task_definition,
                    "lastStatus": "STOPPED",
                    "desiredStatus": "STOPPED",
                },
                {
                    "taskArn": self.previous_task_arns[1],
                    "taskDefinitionArn": self.previous_task_definition,
                    "lastStatus": "STOPPED",
                    "desiredStatus": "STOPPED",
                },
            ],
            "failures": [],
        }
        self.target_health = {
            self.target_group_arn: {
                "TargetHealthDescriptions": [
                    {
                        "Target": {"Id": "10.0.1.10", "Port": 8080},
                        "TargetHealth": {"State": "healthy"},
                    },
                    {
                        "Target": {"Id": "10.0.1.11", "Port": 8080},
                        "TargetHealth": {"State": "healthy"},
                    },
                ]
            }
        }

    def test_completed_replacement_evidence_is_accepted(self):
        verify_production_ecs_evidence(
            self.service,
            self.tasks,
            self.previous_task_arns,
            self.previous_tasks,
            self.target_health,
            self.expected_task_definition,
        )

    def test_previous_revision_running_task_is_rejected(self):
        tasks = copy.deepcopy(self.tasks)
        tasks["tasks"].append(
            {
                "taskArn": "arn:aws:ecs:task/legacy",
                "taskDefinitionArn": self.previous_task_definition,
                "lastStatus": "RUNNING",
                "desiredStatus": "RUNNING",
            }
        )

        with self.assertRaises(DeploymentGateError):
            verify_production_ecs_evidence(
                self.service,
                tasks,
                self.previous_task_arns,
                self.previous_tasks,
                self.target_health,
                self.expected_task_definition,
            )

    def test_previous_task_still_stopping_is_rejected(self):
        previous_tasks = copy.deepcopy(self.previous_tasks)
        previous_tasks["tasks"][0]["lastStatus"] = "STOPPING"

        with self.assertRaises(DeploymentGateError):
            verify_production_ecs_evidence(
                self.service,
                self.tasks,
                self.previous_task_arns,
                previous_tasks,
                self.target_health,
                self.expected_task_definition,
            )

    def test_missing_previous_task_description_is_rejected(self):
        previous_tasks = copy.deepcopy(self.previous_tasks)
        previous_tasks["tasks"].pop()

        with self.assertRaises(DeploymentGateError):
            verify_production_ecs_evidence(
                self.service,
                self.tasks,
                self.previous_task_arns,
                previous_tasks,
                self.target_health,
                self.expected_task_definition,
            )

    def test_pending_task_count_is_rejected(self):
        service = copy.deepcopy(self.service)
        service["pendingCount"] = 1

        with self.assertRaises(DeploymentGateError):
            verify_production_ecs_evidence(
                service,
                self.tasks,
                self.previous_task_arns,
                self.previous_tasks,
                self.target_health,
                self.expected_task_definition,
            )

    def test_draining_target_is_rejected(self):
        target_health = copy.deepcopy(self.target_health)
        target_health[self.target_group_arn]["TargetHealthDescriptions"][0][
            "TargetHealth"
        ]["State"] = "draining"

        with self.assertRaises(DeploymentGateError):
            verify_production_ecs_evidence(
                self.service,
                self.tasks,
                self.previous_task_arns,
                self.previous_tasks,
                target_health,
                self.expected_task_definition,
            )

    def test_missing_target_group_evidence_is_rejected(self):
        with self.assertRaises(DeploymentGateError):
            verify_production_ecs_evidence(
                self.service,
                self.tasks,
                self.previous_task_arns,
                self.previous_tasks,
                {},
                self.expected_task_definition,
            )

    def test_incomplete_healthy_target_set_is_rejected(self):
        target_health = copy.deepcopy(self.target_health)
        target_health[self.target_group_arn]["TargetHealthDescriptions"].pop()

        with self.assertRaises(DeploymentGateError):
            verify_production_ecs_evidence(
                self.service,
                self.tasks,
                self.previous_task_arns,
                self.previous_tasks,
                target_health,
                self.expected_task_definition,
            )

    def _run_cli(self, evidence):
        with tempfile.TemporaryDirectory() as temp_directory:
            evidence_file = Path(temp_directory) / "evidence.json"
            evidence_file.write_text(json.dumps(evidence), encoding="utf-8")
            return subprocess.run(
                [
                    sys.executable,
                    str(Path(__file__).resolve()),
                    "verify-production-ecs",
                    "--evidence-json",
                    str(evidence_file),
                    "--expected-task-definition",
                    self.expected_task_definition,
                ],
                capture_output=True,
                text=True,
            )

    def test_cli_accepts_completed_replacement_evidence(self):
        result = self._run_cli(
            {
                "service": self.service,
                "tasks": self.tasks,
                "previousTaskArns": self.previous_task_arns,
                "previousTasks": self.previous_tasks,
                "targetHealthByArn": self.target_health,
            }
        )

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("production ECS replacement evidence verified", result.stdout)

    def test_cli_fails_closed_for_draining_target(self):
        target_health = copy.deepcopy(self.target_health)
        target_health[self.target_group_arn]["TargetHealthDescriptions"][0][
            "TargetHealth"
        ]["State"] = "draining"

        result = self._run_cli(
            {
                "service": self.service,
                "tasks": self.tasks,
                "previousTaskArns": self.previous_task_arns,
                "previousTasks": self.previous_tasks,
                "targetHealthByArn": target_health,
            }
        )

        self.assertEqual(1, result.returncode)
        self.assertIn("has not completed deregistration", result.stderr)

    def test_cli_fails_closed_without_traceback_for_malformed_evidence_shape(self):
        result = self._run_cli([])

        self.assertEqual(1, result.returncode)
        self.assertNotIn("Traceback", result.stderr)
        self.assertIn("deployment evidence shape is invalid", result.stderr)


class StagingComposeEvidenceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if os.name == "nt":
            git_bash = Path("C:/Program Files/Git/bin/bash.exe")
            cls.bash = str(git_bash) if git_bash.exists() else shutil.which("bash")
        else:
            cls.bash = shutil.which("bash")
        if cls.bash is None:
            raise unittest.SkipTest("bash is required for staging deployment tests")
        cls.deploy_script = Path(__file__).resolve().parents[1] / "deploy" / "deploy.sh"

    def _run_gate(self, docker_body, compose_mysql_count="0"):
        script = f'''
source "{self.deploy_script.as_posix()}"
compose_command() {{
  case "$*" in
    *"ps -q backend") printf '%s\\n' 'new-container' ;;
    *"exec -T mysql"*) printf '%s\\n' '{compose_mysql_count}' ;;
    *) return 2 ;;
  esac
}}
docker() {{
{docker_body}
}}
OLD_BACKEND_CONTAINER_ID='old-container'
OLD_BACKEND_IP='10.0.0.8'
BACKEND_IMAGE='registry.invalid/backend:515531e122ebbce13d8eead4a3ff15a94c25e0b3'
BACKEND_DB_CONNECTION_DRAIN_TIMEOUT_SECONDS=0
verify_old_backend_stopped_and_disconnected
verify_new_backend_replacement
'''
        return subprocess.run(
            [self.bash, "-c", script],
            capture_output=True,
            text=True,
        )

    def test_existing_backend_capture_includes_immutable_image_and_network(self):
        script = f'''
source "{self.deploy_script.as_posix()}"
compose_command() {{
  case "$*" in
    *"ps -q backend") printf '%s\\n' 'old-container' ;;
    *) return 2 ;;
  esac
}}
docker() {{
  if [ "$1" = "inspect" ] && [ "$4" = "old-container" ]; then
    case "$3" in
      *'"miriyum_app"'*) ;;
      *) return 3 ;;
    esac
    printf '%s\\n' 'registry.invalid/backend:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa|10.0.0.8'
  else
    return 2
  fi
}}
capture_existing_backend
printf '%s\\n' "$OLD_BACKEND_CONTAINER_ID|$OLD_BACKEND_IMAGE|$OLD_BACKEND_IP"
'''
        result = subprocess.run(
            [self.bash, "-c", script],
            capture_output=True,
            text=True,
        )

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(
            "old-container|registry.invalid/backend:"
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa|10.0.0.8",
            result.stdout.strip(),
        )

    def test_docker_observation_error_is_not_treated_as_container_removal(self):
        result = self._run_gate(
            '''
  if [ "$1" = "inspect" ] && [ "$4" = "old-container" ]; then
    return 125
  fi
  if [ "$1 $2" = "container ls" ]; then
    return 125
  fi
  if [ "$1" = "inspect" ] && [ "$4" = "new-container" ]; then
    printf '%s\\n' 'true|registry.invalid/backend:515531e122ebbce13d8eead4a3ff15a94c25e0b3'
    return 0
  fi
  return 2
'''
        )

        self.assertEqual(1, result.returncode)
        self.assertIn("container state evidence is unavailable", result.stderr)

    def test_stopped_disconnected_old_container_and_exact_new_image_are_accepted(self):
        result = self._run_gate(
            '''
  if [ "$1" = "inspect" ] && [ "$4" = "old-container" ]; then
    printf '%s\\n' 'false'
  elif [ "$1" = "inspect" ] && [ "$4" = "new-container" ]; then
    printf '%s\\n' 'true|registry.invalid/backend:515531e122ebbce13d8eead4a3ff15a94c25e0b3'
  else
    return 2
  fi
'''
        )

        self.assertEqual(0, result.returncode, result.stderr)

    def test_running_old_container_is_rejected(self):
        result = self._run_gate(
            '''
  if [ "$1" = "inspect" ] && [ "$4" = "old-container" ]; then
    printf '%s\\n' 'true'
  elif [ "$1" = "inspect" ] && [ "$4" = "new-container" ]; then
    printf '%s\\n' 'true|registry.invalid/backend:515531e122ebbce13d8eead4a3ff15a94c25e0b3'
  else
    return 2
  fi
'''
        )

        self.assertEqual(1, result.returncode)
        self.assertIn("still running", result.stderr)

    def test_old_database_connection_is_rejected(self):
        result = self._run_gate(
            '''
  if [ "$1" = "inspect" ] && [ "$4" = "old-container" ]; then
    printf '%s\\n' 'false'
  elif [ "$1" = "inspect" ] && [ "$4" = "new-container" ]; then
    printf '%s\\n' 'true|registry.invalid/backend:515531e122ebbce13d8eead4a3ff15a94c25e0b3'
  else
    return 2
  fi
''',
            compose_mysql_count="1",
        )

        self.assertEqual(1, result.returncode)
        self.assertIn("database connections remain", result.stderr)

    def test_unexpected_new_backend_image_is_rejected(self):
        result = self._run_gate(
            '''
  if [ "$1" = "inspect" ] && [ "$4" = "old-container" ]; then
    printf '%s\\n' 'false'
  elif [ "$1" = "inspect" ] && [ "$4" = "new-container" ]; then
    printf '%s\\n' 'true|registry.invalid/backend:legacy'
  else
    return 2
  fi
'''
        )

        self.assertEqual(1, result.returncode)
        self.assertIn("selected immutable image", result.stderr)


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1].startswith("verify-"):
        raise SystemExit(run_cli(sys.argv[1:]))
    unittest.main()
