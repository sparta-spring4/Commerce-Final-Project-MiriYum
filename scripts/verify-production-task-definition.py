import argparse
import json
import re
from pathlib import Path


def required_environment_variables(application_config_path):
    if application_config_path is None:
        return set()

    application_config = Path(application_config_path).read_text(encoding="utf-8")
    return set(re.findall(r"\$\{(MIRIYUM_[A-Z0-9_]+)\}", application_config))


def validate(contract_path, task_definition_path, application_config_path=None):
    contract = json.loads(Path(contract_path).read_text(encoding="utf-8"))
    task_definition = json.loads(Path(task_definition_path).read_text(encoding="utf-8"))
    backend = next(
        (
            container
            for container in task_definition.get("containerDefinitions", [])
            if container.get("name") == "backend"
        ),
        None,
    )
    if backend is None:
        return ["Missing backend container definition"]

    environment = {
        item["name"]: str(item.get("value", ""))
        for item in backend.get("environment", [])
        if "name" in item
    }
    secrets = {
        item["name"]: item.get("valueFrom", "")
        for item in backend.get("secrets", [])
        if "name" in item
    }
    errors = []

    if (
        "FARGATE" not in task_definition.get("requiresCompatibilities", [])
        or task_definition.get("networkMode") != "awsvpc"
        or task_definition.get("runtimePlatform", {}).get("cpuArchitecture") != "ARM64"
    ):
        errors.append("Task definition must declare ARM64 Fargate runtime")

    for name in contract.get("requiredSecrets", []):
        if name not in secrets:
            errors.append(f"Missing secret reference: {name}")

    required_contract_secrets = set(contract.get("requiredSecrets", []))
    for name in sorted(required_environment_variables(application_config_path)):
        if name not in required_contract_secrets:
            errors.append(f"Missing required secret contract entry for application setting: {name}")

    for feature_flag, required_secrets in contract.get("conditionalSecrets", {}).items():
        if environment.get(feature_flag, "false").lower() != "true":
            continue
        for name in required_secrets:
            if name not in secrets:
                errors.append(f"Missing secret reference: {name}")

    for feature_flag, required_environment in contract.get("conditionalEnvironment", {}).items():
        if environment.get(feature_flag, "false").lower() != "true":
            continue
        for name in required_environment:
            if not environment.get(name, ""):
                errors.append(f"Missing environment value: {name}")

    for name, value_from in secrets.items():
        if not value_from.endswith(f":{name}::"):
            errors.append(f"Secret reference must select its matching JSON key: {name}")

    return errors


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("contract")
    parser.add_argument("task_definition")
    parser.add_argument("--application-config")
    arguments = parser.parse_args()

    errors = validate(
        arguments.contract,
        arguments.task_definition,
        arguments.application_config,
    )
    if errors:
        for error in errors:
            print(error)
        raise SystemExit(1)


if __name__ == "__main__":
    main()
