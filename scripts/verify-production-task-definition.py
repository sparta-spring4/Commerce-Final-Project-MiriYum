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

    required_secrets = set(contract.get("requiredSecrets", [])) | set(contract.get("parameterSecrets", []))
    for name in required_secrets:
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

    conditional_parameter_secrets = set()
    for feature_flag, required_secrets in contract.get("conditionalParameterSecrets", {}).items():
        if environment.get(feature_flag, "false").lower() != "true":
            for name in required_secrets:
                if name in secrets:
                    errors.append(
                        f"Conditional parameter secret must be absent when disabled: {name}"
                    )
            continue
        conditional_parameter_secrets.update(required_secrets)
        for name in required_secrets:
            if name not in secrets:
                errors.append(f"Missing parameter secret reference: {name}")

    for feature_flag, required_environment in contract.get("conditionalEnvironment", {}).items():
        if environment.get(feature_flag, "false").lower() != "true":
            continue
        for name in required_environment:
            if not environment.get(name, ""):
                errors.append(f"Missing environment value: {name}")

    parameter_secrets = (
        set(contract.get("parameterSecrets", []))
        | set(contract.get("parameterReferencePaths", {}))
    )
    parameter_reference_paths = contract.get("parameterReferencePaths", {})
    secret_prefixes = set()
    for name, value_from in secrets.items():
        if name in parameter_secrets:
            expected_path = parameter_reference_paths.get(name)
            if not expected_path:
                errors.append(f"Missing parameter reference contract entry: {name}")
                continue
            expected_placeholder = f"REPLACE_WITH_{name}_PARAMETER_ARN"
            if value_from == expected_placeholder:
                continue
            parameter_match = re.fullmatch(
                r"arn:aws:ssm:[a-z0-9-]+:\d{12}:parameter/(.+)",
                value_from,
            )
            if not parameter_match or parameter_match.group(1) != expected_path:
                errors.append(f"Parameter reference does not match contract path: {name}")
            continue
        if not value_from.endswith(f":{name}::"):
            errors.append(f"Secret reference must select its matching JSON key: {name}")
            continue
        secret_prefixes.add(value_from[: -len(f":{name}::")])

    if len(secret_prefixes) > 1:
        errors.append("Secret references must use one shared Secret ARN")

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
