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


def select_previous_task_arns(running_task_arns, stopped_task_arns, stopped_tasks):
    for label, task_arns, allow_empty in (
        ("running", running_task_arns, False),
        ("stopped", stopped_task_arns, True),
    ):
        if (
            not isinstance(task_arns, list)
            or (not allow_empty and not task_arns)
            or any(not isinstance(task_arn, str) or not task_arn for task_arn in task_arns)
            or len(set(task_arns)) != len(task_arns)
        ):
            raise DeploymentGateError(f"{label} ECS task identity evidence is invalid")

    if not isinstance(stopped_tasks, dict):
        raise DeploymentGateError("stopped ECS task lifecycle evidence is invalid")
    if stopped_tasks.get("failures"):
        raise DeploymentGateError("stopped ECS task evidence contains lookup failures")

    described_stopped_tasks = stopped_tasks.get("tasks", [])
    if not isinstance(described_stopped_tasks, list):
        raise DeploymentGateError("stopped ECS task lifecycle evidence is invalid")
    described_stopped_task_arns = {
        task.get("taskArn")
        for task in described_stopped_tasks
        if isinstance(task, dict) and task.get("taskArn")
    }
    if (
        described_stopped_task_arns != set(stopped_task_arns)
        or len(described_stopped_tasks) != len(stopped_task_arns)
        or any(
            not isinstance(task, dict)
            or task.get("desiredStatus") != "STOPPED"
            or not isinstance(task.get("lastStatus"), str)
            or not task.get("lastStatus")
            for task in described_stopped_tasks
        )
    ):
        raise DeploymentGateError("stopped ECS task lifecycle evidence is incomplete")

    selected_task_arns = list(running_task_arns)
    selected_task_arn_set = set(selected_task_arns)
    stopped_tasks_by_arn = {
        task["taskArn"]: task for task in described_stopped_tasks
    }
    for task_arn in stopped_task_arns:
        if (
            stopped_tasks_by_arn[task_arn]["lastStatus"] != "STOPPED"
            and task_arn not in selected_task_arn_set
        ):
            selected_task_arns.append(task_arn)
            selected_task_arn_set.add(task_arn)

    return sorted(selected_task_arns)


def verify_production_ecs_evidence(
    service,
    tasks,
    previous_task_arns,
    previous_tasks,
    target_health_by_arn,
    expected_task_definition,
    final_stopped_task_arns=None,
    final_stopped_tasks=None,
    traffic_target_group_arns=None,
):
    if final_stopped_task_arns is None:
        final_stopped_task_arns = []
    if final_stopped_tasks is None:
        final_stopped_tasks = {"tasks": [], "failures": []}
    if not all(
        isinstance(value, dict)
        for value in (
            service,
            tasks,
            previous_tasks,
            final_stopped_tasks,
            target_health_by_arn,
        )
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

    current_task_target_ids = set()
    for task in current_tasks:
        private_ipv4_addresses = [
            detail.get("value")
            for attachment in task.get("attachments", [])
            if attachment.get("type") == "ElasticNetworkInterface"
            and attachment.get("status") == "ATTACHED"
            for detail in attachment.get("details", [])
            if detail.get("name") == "privateIPv4Address" and detail.get("value")
        ]
        if len(private_ipv4_addresses) != 1:
            raise DeploymentGateError(
                "current ECS task target identity evidence is incomplete"
            )
        current_task_target_ids.add(private_ipv4_addresses[0])

    if len(current_task_target_ids) != desired_count:
        raise DeploymentGateError("current ECS task target identities are not unique")

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

    if (
        not isinstance(final_stopped_task_arns, list)
        or any(
            not isinstance(task_arn, str) or not task_arn
            for task_arn in final_stopped_task_arns
        )
        or len(set(final_stopped_task_arns)) != len(final_stopped_task_arns)
    ):
        raise DeploymentGateError("final stopped ECS task identity evidence is invalid")
    if final_stopped_tasks.get("failures"):
        raise DeploymentGateError(
            "final stopped ECS task evidence contains lookup failures"
        )

    described_final_stopped_tasks = final_stopped_tasks.get("tasks", [])
    if not isinstance(described_final_stopped_tasks, list):
        raise DeploymentGateError(
            "final stopped ECS task lifecycle evidence is incomplete"
        )
    described_final_stopped_task_arns = {
        task.get("taskArn")
        for task in described_final_stopped_tasks
        if isinstance(task, dict) and task.get("taskArn")
    }
    if (
        described_final_stopped_task_arns != set(final_stopped_task_arns)
        or len(described_final_stopped_tasks) != len(final_stopped_task_arns)
    ):
        raise DeploymentGateError(
            "final stopped ECS task lifecycle evidence is incomplete"
        )
    if any(
        task.get("desiredStatus") != "STOPPED"
        or task.get("lastStatus") != "STOPPED"
        for task in described_final_stopped_tasks
    ):
        raise DeploymentGateError("a nonterminal stopped ECS task remains")

    target_group_arns = set()
    primary_target_group_arns = set()
    for load_balancer in service.get("loadBalancers", []):
        primary_target_group_arn = load_balancer.get("targetGroupArn")
        if primary_target_group_arn:
            target_group_arns.add(primary_target_group_arn)
            primary_target_group_arns.add(primary_target_group_arn)

        alternate_target_group_arn = load_balancer.get(
            "advancedConfiguration", {}
        ).get("alternateTargetGroupArn")
        if alternate_target_group_arn:
            target_group_arns.add(alternate_target_group_arn)

    if not target_group_arns:
        raise DeploymentGateError("ECS target group evidence is missing")

    if traffic_target_group_arns is None:
        traffic_target_group_arns = primary_target_group_arns
    elif (
        not isinstance(traffic_target_group_arns, list)
        or not traffic_target_group_arns
        or any(
            not isinstance(target_group_arn, str) or not target_group_arn
            for target_group_arn in traffic_target_group_arns
        )
        or not set(traffic_target_group_arns).issubset(target_group_arns)
    ):
        raise DeploymentGateError("ECS traffic target group evidence is invalid")

    for target_group_arn in set(traffic_target_group_arns):
        target_health = target_health_by_arn.get(target_group_arn)
        if target_health is None:
            raise DeploymentGateError("ECS target group health evidence is missing")

        descriptions = target_health.get("TargetHealthDescriptions", [])
        healthy_target_ids = {
            description.get("Target", {}).get("Id")
            for description in descriptions
            if description.get("TargetHealth", {}).get("State") == "healthy"
            and description.get("Target", {}).get("Id")
        }
        if len(descriptions) != desired_count or any(
            description.get("TargetHealth", {}).get("State") != "healthy"
            for description in descriptions
        ) or healthy_target_ids != current_task_target_ids:
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
    selection_parser = subparsers.add_parser("select-previous-ecs-tasks")
    selection_parser.add_argument("--evidence-json", required=True)
    selection_parser.add_argument("--output-json", required=True)
    options = parser.parse_args(arguments)

    try:
        if options.command == "verify-ancestor":
            verify_compatible_revision(
                Path(options.repository),
                options.minimum_sha,
                options.candidate_sha,
            )
        elif options.command == "verify-production-ecs":
            evidence = json.loads(
                Path(options.evidence_json).read_text(encoding="utf-8")
            )
            if not isinstance(evidence, dict):
                raise DeploymentGateError("deployment evidence shape is invalid")
            if not all(
                key in evidence for key in ("stoppedTaskArns", "stoppedTasks")
            ):
                raise DeploymentGateError("final stopped ECS task evidence is missing")
            verify_production_ecs_evidence(
                evidence.get("service", {}),
                evidence.get("tasks", {}),
                evidence.get("previousTaskArns", []),
                evidence.get("previousTasks", {}),
                evidence.get("targetHealthByArn", {}),
                options.expected_task_definition,
                evidence.get("stoppedTaskArns", []),
                evidence.get("stoppedTasks", {}),
                evidence.get("trafficTargetGroupArns"),
            )
        else:
            evidence = json.loads(
                Path(options.evidence_json).read_text(encoding="utf-8")
            )
            if not isinstance(evidence, dict):
                raise DeploymentGateError("deployment evidence shape is invalid")
            selected_task_arns = select_previous_task_arns(
                evidence.get("runningTaskArns", []),
                evidence.get("stoppedTaskArns", []),
                evidence.get("stoppedTasks", {}),
            )
            Path(options.output_json).write_text(
                json.dumps(selected_task_arns),
                encoding="utf-8",
            )
    except (DeploymentGateError, OSError, json.JSONDecodeError) as error:
        print(f"Notification read deployment gate failed: {error}", file=sys.stderr)
        return 1

    if options.command == "verify-ancestor":
        print(
            "Notification read minimum compatible writer revision verified: "
            f"{options.candidate_sha}"
        )
    elif options.command == "verify-production-ecs":
        print("Notification read production ECS replacement evidence verified.")
    else:
        print("Notification read previous ECS task identities selected.")
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


class PreviousEcsTaskSelectionTest(unittest.TestCase):
    def setUp(self):
        self.running_task_arns = [
            "arn:aws:ecs:task/current-1",
            "arn:aws:ecs:task/current-2",
        ]
        self.transitioning_task_arn = "arn:aws:ecs:task/transitioning"
        self.stopped_task_arn = "arn:aws:ecs:task/stopped"
        self.stopped_task_arns = [
            self.transitioning_task_arn,
            self.stopped_task_arn,
        ]

    def _stopped_tasks(self, transitioning_status="STOPPING"):
        return {
            "tasks": [
                {
                    "taskArn": self.transitioning_task_arn,
                    "desiredStatus": "STOPPED",
                    "lastStatus": transitioning_status,
                },
                {
                    "taskArn": self.stopped_task_arn,
                    "desiredStatus": "STOPPED",
                    "lastStatus": "STOPPED",
                },
            ],
            "failures": [],
        }

    def test_stopped_desired_nonterminal_tasks_are_captured(self):
        for last_status in ("RUNNING", "DEACTIVATING", "STOPPING"):
            with self.subTest(last_status=last_status):
                selected = select_previous_task_arns(
                    self.running_task_arns,
                    self.stopped_task_arns,
                    self._stopped_tasks(last_status),
                )

                self.assertEqual(
                    [*self.running_task_arns, self.transitioning_task_arn],
                    selected,
                )

    def test_fully_stopped_history_is_not_captured(self):
        stopped_tasks = self._stopped_tasks("STOPPED")

        selected = select_previous_task_arns(
            self.running_task_arns,
            self.stopped_task_arns,
            stopped_tasks,
        )

        self.assertEqual(self.running_task_arns, selected)

    def test_selection_is_canonical_for_snapshot_comparison(self):
        selected = select_previous_task_arns(
            list(reversed(self.running_task_arns)),
            self.stopped_task_arns,
            self._stopped_tasks(),
        )

        self.assertEqual(
            sorted([*self.running_task_arns, self.transitioning_task_arn]),
            selected,
        )

    def test_missing_stopped_task_description_is_rejected(self):
        stopped_tasks = self._stopped_tasks()
        stopped_tasks["tasks"].pop()

        with self.assertRaises(DeploymentGateError):
            select_previous_task_arns(
                self.running_task_arns,
                self.stopped_task_arns,
                stopped_tasks,
            )

    def test_stopped_task_lookup_failure_is_rejected(self):
        stopped_tasks = self._stopped_tasks()
        stopped_tasks["failures"].append({"arn": self.stopped_task_arn})

        with self.assertRaises(DeploymentGateError):
            select_previous_task_arns(
                self.running_task_arns,
                self.stopped_task_arns,
                stopped_tasks,
            )

    def test_cli_writes_selected_task_identities(self):
        with tempfile.TemporaryDirectory() as temp_directory:
            evidence_file = Path(temp_directory) / "evidence.json"
            output_file = Path(temp_directory) / "selected.json"
            evidence_file.write_text(
                json.dumps(
                    {
                        "runningTaskArns": self.running_task_arns,
                        "stoppedTaskArns": self.stopped_task_arns,
                        "stoppedTasks": self._stopped_tasks(),
                    }
                ),
                encoding="utf-8",
            )

            result = subprocess.run(
                [
                    sys.executable,
                    str(Path(__file__).resolve()),
                    "select-previous-ecs-tasks",
                    "--evidence-json",
                    str(evidence_file),
                    "--output-json",
                    str(output_file),
                ],
                capture_output=True,
                text=True,
            )

            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(
                [*self.running_task_arns, self.transitioning_task_arn],
                json.loads(output_file.read_text(encoding="utf-8")),
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
                    "attachments": [
                        {
                            "id": "eni-attachment-current-1",
                            "type": "ElasticNetworkInterface",
                            "status": "ATTACHED",
                            "details": [
                                {"name": "subnetId", "value": "subnet-test"},
                                {
                                    "name": "networkInterfaceId",
                                    "value": "eni-current-1",
                                },
                                {
                                    "name": "privateIPv4Address",
                                    "value": "10.0.1.10",
                                },
                            ],
                        }
                    ],
                },
                {
                    "taskArn": "arn:aws:ecs:task/current-2",
                    "taskDefinitionArn": self.expected_task_definition,
                    "lastStatus": "RUNNING",
                    "desiredStatus": "RUNNING",
                    "attachments": [
                        {
                            "id": "eni-attachment-current-2",
                            "type": "ElasticNetworkInterface",
                            "status": "ATTACHED",
                            "details": [
                                {"name": "subnetId", "value": "subnet-test"},
                                {
                                    "name": "networkInterfaceId",
                                    "value": "eni-current-2",
                                },
                                {
                                    "name": "privateIPv4Address",
                                    "value": "10.0.1.11",
                                },
                            ],
                        }
                    ],
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

    def test_active_blue_green_target_group_is_accepted_when_inactive_group_is_empty(self):
        inactive_target_group_arn = (
            "arn:aws:elasticloadbalancing:ap-northeast-2:123456789012:"
            "targetgroup/miriyum-inactive/abcdef"
        )
        service = copy.deepcopy(self.service)
        service["loadBalancers"][0]["advancedConfiguration"] = {
            "alternateTargetGroupArn": inactive_target_group_arn
        }
        target_health = copy.deepcopy(self.target_health)
        target_health[inactive_target_group_arn] = {"TargetHealthDescriptions": []}

        verify_production_ecs_evidence(
            service,
            self.tasks,
            self.previous_task_arns,
            self.previous_tasks,
            target_health,
            self.expected_task_definition,
            traffic_target_group_arns=[self.target_group_arn],
        )

    def test_empty_listener_traffic_target_groups_are_rejected(self):
        with self.assertRaisesRegex(
            DeploymentGateError, "ECS traffic target group evidence is invalid"
        ):
            verify_production_ecs_evidence(
                self.service,
                self.tasks,
                self.previous_task_arns,
                self.previous_tasks,
                self.target_health,
                self.expected_task_definition,
                traffic_target_group_arns=[],
            )

    def test_listener_target_group_outside_ecs_service_is_rejected(self):
        unexpected_target_group_arn = (
            "arn:aws:elasticloadbalancing:ap-northeast-2:123456789012:"
            "targetgroup/miriyum-unexpected/abcdef"
        )
        with self.assertRaisesRegex(
            DeploymentGateError, "ECS traffic target group evidence is invalid"
        ):
            verify_production_ecs_evidence(
                self.service,
                self.tasks,
                self.previous_task_arns,
                self.previous_tasks,
                self.target_health,
                self.expected_task_definition,
                traffic_target_group_arns=[unexpected_target_group_arn],
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

    def test_new_task_missing_from_target_group_is_rejected(self):
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

    def test_stale_manual_target_mixed_with_current_task_is_rejected(self):
        target_health = copy.deepcopy(self.target_health)
        target_health[self.target_group_arn]["TargetHealthDescriptions"][1][
            "Target"
        ]["Id"] = "10.0.9.99"

        with self.assertRaises(DeploymentGateError):
            verify_production_ecs_evidence(
                self.service,
                self.tasks,
                self.previous_task_arns,
                self.previous_tasks,
                target_health,
                self.expected_task_definition,
            )

    def test_equal_target_count_with_different_identities_is_rejected(self):
        target_health = copy.deepcopy(self.target_health)
        target_health[self.target_group_arn]["TargetHealthDescriptions"][0][
            "Target"
        ]["Id"] = "10.0.9.98"
        target_health[self.target_group_arn]["TargetHealthDescriptions"][1][
            "Target"
        ]["Id"] = "10.0.9.99"

        with self.assertRaises(DeploymentGateError):
            verify_production_ecs_evidence(
                self.service,
                self.tasks,
                self.previous_task_arns,
                self.previous_tasks,
                target_health,
                self.expected_task_definition,
            )

    def test_current_task_without_private_ip_evidence_is_rejected(self):
        tasks = copy.deepcopy(self.tasks)
        tasks["tasks"][0]["attachments"][0]["details"] = [
            {"name": "networkInterfaceId", "value": "eni-current-1"}
        ]

        with self.assertRaises(DeploymentGateError):
            verify_production_ecs_evidence(
                self.service,
                tasks,
                self.previous_task_arns,
                self.previous_tasks,
                self.target_health,
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
                "stoppedTaskArns": [],
                "stoppedTasks": {"tasks": [], "failures": []},
                "targetHealthByArn": self.target_health,
            }
        )

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("production ECS replacement evidence verified", result.stdout)

    def test_cli_accepts_the_active_blue_green_target_group(self):
        inactive_target_group_arn = (
            "arn:aws:elasticloadbalancing:ap-northeast-2:123456789012:"
            "targetgroup/miriyum-inactive/abcdef"
        )
        service = copy.deepcopy(self.service)
        service["loadBalancers"][0] = {
            "targetGroupArn": inactive_target_group_arn,
            "advancedConfiguration": {
                "alternateTargetGroupArn": self.target_group_arn
            },
        }
        target_health = copy.deepcopy(self.target_health)
        target_health[inactive_target_group_arn] = {"TargetHealthDescriptions": []}

        result = self._run_cli(
            {
                "service": service,
                "tasks": self.tasks,
                "previousTaskArns": self.previous_task_arns,
                "previousTasks": self.previous_tasks,
                "stoppedTaskArns": [],
                "stoppedTasks": {"tasks": [], "failures": []},
                "trafficTargetGroupArns": [self.target_group_arn],
                "targetHealthByArn": target_health,
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
                "stoppedTaskArns": [],
                "stoppedTasks": {"tasks": [], "failures": []},
                "targetHealthByArn": target_health,
            }
        )

        self.assertEqual(1, result.returncode)
        self.assertIn("has not completed deregistration", result.stderr)

    def test_cli_rejects_empty_listener_traffic_target_groups(self):
        result = self._run_cli(
            {
                "service": self.service,
                "tasks": self.tasks,
                "previousTaskArns": self.previous_task_arns,
                "previousTasks": self.previous_tasks,
                "stoppedTaskArns": [],
                "stoppedTasks": {"tasks": [], "failures": []},
                "trafficTargetGroupArns": [],
                "targetHealthByArn": self.target_health,
            }
        )

        self.assertEqual(1, result.returncode)
        self.assertIn("ECS traffic target group evidence is invalid", result.stderr)

    def test_cli_rejects_listener_target_group_outside_ecs_service(self):
        unexpected_target_group_arn = (
            "arn:aws:elasticloadbalancing:ap-northeast-2:123456789012:"
            "targetgroup/miriyum-unexpected/abcdef"
        )
        result = self._run_cli(
            {
                "service": self.service,
                "tasks": self.tasks,
                "previousTaskArns": self.previous_task_arns,
                "previousTasks": self.previous_tasks,
                "stoppedTaskArns": [],
                "stoppedTasks": {"tasks": [], "failures": []},
                "trafficTargetGroupArns": [unexpected_target_group_arn],
                "targetHealthByArn": self.target_health,
            }
        )

        self.assertEqual(1, result.returncode)
        self.assertIn("ECS traffic target group evidence is invalid", result.stderr)

    def test_cli_fails_closed_without_traceback_for_malformed_evidence_shape(self):
        result = self._run_cli([])

        self.assertEqual(1, result.returncode)
        self.assertNotIn("Traceback", result.stderr)
        self.assertIn("deployment evidence shape is invalid", result.stderr)

    def test_cli_rejects_uncaptured_nonterminal_stopped_task(self):
        stopped_task_arn = "arn:aws:ecs:task/replacement-after-snapshot"
        result = self._run_cli(
            {
                "service": self.service,
                "tasks": self.tasks,
                "previousTaskArns": self.previous_task_arns,
                "previousTasks": self.previous_tasks,
                "stoppedTaskArns": [stopped_task_arn],
                "stoppedTasks": {
                    "tasks": [
                        {
                            "taskArn": stopped_task_arn,
                            "desiredStatus": "STOPPED",
                            "lastStatus": "STOPPING",
                        }
                    ],
                    "failures": [],
                },
                "targetHealthByArn": self.target_health,
            }
        )

        self.assertEqual(1, result.returncode)
        self.assertIn("nonterminal stopped ECS task", result.stderr)

    def test_cli_rejects_incomplete_final_stopped_task_evidence(self):
        result = self._run_cli(
            {
                "service": self.service,
                "tasks": self.tasks,
                "previousTaskArns": self.previous_task_arns,
                "previousTasks": self.previous_tasks,
                "stoppedTaskArns": ["arn:aws:ecs:task/missing"],
                "stoppedTasks": {"tasks": [], "failures": []},
                "targetHealthByArn": self.target_health,
            }
        )

        self.assertEqual(1, result.returncode)
        self.assertIn("final stopped ECS task lifecycle evidence", result.stderr)


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
    if len(sys.argv) > 1 and sys.argv[1] in {
        "select-previous-ecs-tasks",
        "verify-ancestor",
        "verify-production-ecs",
    }:
        raise SystemExit(run_cli(sys.argv[1:]))
    unittest.main()
