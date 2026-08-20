import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


SCRIPT_PATH = Path(__file__).with_name("verify-production-task-definition.py")


def load_validator():
    spec = importlib.util.spec_from_file_location("verify_production_task_definition", SCRIPT_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.validate


class VerifyProductionTaskDefinitionTest(unittest.TestCase):
    def write_json(self, directory, name, content):
        path = Path(directory, name)
        path.write_text(json.dumps(content), encoding="utf-8")
        return path

    def write_text(self, directory, name, content):
        path = Path(directory, name)
        path.write_text(content, encoding="utf-8")
        return path

    def test_accepts_required_secret_references_and_disabled_optional_features(self):
        validate = load_validator()

        with tempfile.TemporaryDirectory() as directory:
            contract = self.write_json(
                    directory,
                    "contract.json",
                    {
                        "requiredSecrets": ["MIRIYUM_DB_PASSWORD", "MIRIYUM_JWT_SECRET"],
                        "conditionalSecrets": {
                            "MIRIYUM_KAKAO_ENABLED": ["MIRIYUM_KAKAO_REST_API_KEY"]
                        },
                        "conditionalEnvironment": {
                            "MIRIYUM_KAKAO_ENABLED": ["MIRIYUM_KAKAO_REDIRECT_URIS"]
                        },
                    },
            )
            task_definition = self.write_json(
                    directory,
                    "task-definition.json",
                    {
                        "requiresCompatibilities": ["FARGATE"],
                        "networkMode": "awsvpc",
                        "runtimePlatform": {"cpuArchitecture": "ARM64"},
                        "containerDefinitions": [
                            {
                                "name": "backend",
                                "environment": [
                                    {"name": "MIRIYUM_KAKAO_ENABLED", "value": "false"}
                                ],
                                "secrets": [
                                    {
                                        "name": "MIRIYUM_DB_PASSWORD",
                                        "valueFrom": "REPLACE_WITH_APPLICATION_SECRET_ARN:MIRIYUM_DB_PASSWORD::",
                                    },
                                    {
                                        "name": "MIRIYUM_JWT_SECRET",
                                        "valueFrom": "REPLACE_WITH_APPLICATION_SECRET_ARN:MIRIYUM_JWT_SECRET::",
                                    },
                                ],
                            }
                        ]
                    },
            )

            self.assertEqual([], validate(contract, task_definition))

    def test_requires_optional_secret_when_its_feature_is_enabled(self):
        validate = load_validator()

        with tempfile.TemporaryDirectory() as directory:
            contract = self.write_json(
                    directory,
                    "contract.json",
                    {
                        "requiredSecrets": [],
                        "conditionalSecrets": {
                            "MIRIYUM_KAKAO_ENABLED": ["MIRIYUM_KAKAO_REST_API_KEY"]
                        },
                        "conditionalEnvironment": {
                            "MIRIYUM_KAKAO_ENABLED": ["MIRIYUM_KAKAO_REDIRECT_URIS"]
                        },
                    },
            )
            task_definition = self.write_json(
                    directory,
                    "task-definition.json",
                    {
                        "requiresCompatibilities": ["FARGATE"],
                        "networkMode": "awsvpc",
                        "runtimePlatform": {"cpuArchitecture": "ARM64"},
                        "containerDefinitions": [
                            {
                                "name": "backend",
                                "environment": [
                                    {"name": "MIRIYUM_KAKAO_ENABLED", "value": "true"}
                                ],
                                "secrets": [],
                            }
                        ]
                    },
            )

            self.assertEqual(
                    [
                        "Missing secret reference: MIRIYUM_KAKAO_REST_API_KEY",
                        "Missing environment value: MIRIYUM_KAKAO_REDIRECT_URIS",
                    ],
                    validate(contract, task_definition),
            )

    def test_requires_arm64_fargate_runtime(self):
        validate = load_validator()

        with tempfile.TemporaryDirectory() as directory:
            contract = self.write_json(directory, "contract.json", {"requiredSecrets": []})
            task_definition = self.write_json(
                    directory,
                    "task-definition.json",
                    {
                        "requiresCompatibilities": ["FARGATE"],
                        "networkMode": "awsvpc",
                        "runtimePlatform": {"cpuArchitecture": "X86_64"},
                        "containerDefinitions": [{"name": "backend", "secrets": []}],
                    },
            )

            self.assertEqual(
                    ["Task definition must declare ARM64 Fargate runtime"],
                    validate(contract, task_definition),
            )

    def test_rejects_secret_reference_that_selects_a_different_json_key(self):
        validate = load_validator()

        with tempfile.TemporaryDirectory() as directory:
            contract = self.write_json(
                    directory,
                    "contract.json",
                    {"requiredSecrets": ["MIRIYUM_DB_PASSWORD"]},
            )
            task_definition = self.write_json(
                    directory,
                    "task-definition.json",
                    {
                        "requiresCompatibilities": ["FARGATE"],
                        "networkMode": "awsvpc",
                        "runtimePlatform": {"cpuArchitecture": "ARM64"},
                        "containerDefinitions": [
                            {
                                "name": "backend",
                                "secrets": [
                                    {
                                        "name": "MIRIYUM_DB_PASSWORD",
                                        "valueFrom": "REPLACE:MIRIYUM_JWT_SECRET::",
                                    }
                                ],
                            }
                        ],
                    },
            )

            self.assertEqual(
                    [
                        "Secret reference must select its matching JSON key: "
                        "MIRIYUM_DB_PASSWORD"
                    ],
                    validate(contract, task_definition),
            )

    def test_requires_contract_entry_for_application_setting_without_default(self):
        validate = load_validator()

        with tempfile.TemporaryDirectory() as directory:
            contract = self.write_json(
                    directory,
                    "contract.json",
                    {"requiredSecrets": ["MIRIYUM_DB_URL"]},
            )
            task_definition = self.write_json(
                    directory,
                    "task-definition.json",
                    {
                        "requiresCompatibilities": ["FARGATE"],
                        "networkMode": "awsvpc",
                        "runtimePlatform": {"cpuArchitecture": "ARM64"},
                        "containerDefinitions": [
                            {
                                "name": "backend",
                                "secrets": [
                                    {
                                        "name": "MIRIYUM_DB_URL",
                                        "valueFrom": "REPLACE:MIRIYUM_DB_URL::",
                                    }
                                ],
                            }
                        ],
                    },
            )
            application_config = self.write_text(
                    directory,
                    "application.yml",
                    "url: ${MIRIYUM_DB_URL}\nsecret: ${MIRIYUM_NEW_REQUIRED_SECRET}\n"
                    "optional: ${MIRIYUM_OPTIONAL_SECRET:default-value}\n",
            )

            self.assertEqual(
                    [
                        "Missing required secret contract entry for application setting: "
                        "MIRIYUM_NEW_REQUIRED_SECRET"
                    ],
                    validate(contract, task_definition, application_config),
            )

    def test_rejects_secret_references_with_different_secret_prefixes(self):
        validate = load_validator()

        with tempfile.TemporaryDirectory() as directory:
            contract = self.write_json(
                    directory,
                    "contract.json",
                    {"requiredSecrets": ["MIRIYUM_DB_URL", "MIRIYUM_DB_PASSWORD"]},
            )
            task_definition = self.write_json(
                    directory,
                    "task-definition.json",
                    {
                        "requiresCompatibilities": ["FARGATE"],
                        "networkMode": "awsvpc",
                        "runtimePlatform": {"cpuArchitecture": "ARM64"},
                        "containerDefinitions": [
                            {
                                "name": "backend",
                                "secrets": [
                                    {
                                        "name": "MIRIYUM_DB_URL",
                                        "valueFrom": "FIRST_SECRET:MIRIYUM_DB_URL::",
                                    },
                                    {
                                        "name": "MIRIYUM_DB_PASSWORD",
                                        "valueFrom": "SECOND_SECRET:MIRIYUM_DB_PASSWORD::",
                                    },
                                ],
                            }
                        ],
                    },
            )

            self.assertEqual(
                    ["Secret references must use one shared Secret ARN"],
                    validate(contract, task_definition),
            )

    def test_accepts_a_secure_parameter_reference_separate_from_json_secrets(self):
        validate = load_validator()

        with tempfile.TemporaryDirectory() as directory:
            contract = self.write_json(
                    directory,
                    "contract.json",
                    {
                        "requiredSecrets": ["MIRIYUM_DB_PASSWORD"],
                        "parameterSecrets": ["OPENAI_API_KEY"],
                        "parameterReferencePaths": {
                            "OPENAI_API_KEY": "miriyum/shared/openai-api-key"
                        },
                    },
            )
            task_definition = self.write_json(
                    directory,
                    "task-definition.json",
                    {
                        "requiresCompatibilities": ["FARGATE"],
                        "networkMode": "awsvpc",
                        "runtimePlatform": {"cpuArchitecture": "ARM64"},
                        "containerDefinitions": [
                            {
                                "name": "backend",
                                "secrets": [
                                    {
                                        "name": "MIRIYUM_DB_PASSWORD",
                                        "valueFrom": "APPLICATION:MIRIYUM_DB_PASSWORD::",
                                    },
                                    {
                                        "name": "OPENAI_API_KEY",
                                        "valueFrom": "arn:aws:ssm:ap-northeast-2:123456789012:parameter/miriyum/shared/openai-api-key",
                                    },
                                ],
                            }
                        ],
                    },
            )

            self.assertEqual([], validate(contract, task_definition))

    def test_allows_llm_disabled_task_without_openai_secret(self):
        validate = load_validator()

        with tempfile.TemporaryDirectory() as directory:
            contract = self.write_json(
                    directory,
                    "contract.json",
                    {
                        "requiredSecrets": [],
                        "parameterSecrets": [],
                        "conditionalParameterSecrets": {
                            "MIRIYUM_STORE_SEARCH_LLM_ENABLED": ["OPENAI_API_KEY"]
                        },
                        "parameterReferencePaths": {
                            "OPENAI_API_KEY": "miriyum/shared/openai-api-key"
                        },
                    },
            )
            task_definition = self.write_json(
                    directory,
                    "task-definition.json",
                    {
                        "requiresCompatibilities": ["FARGATE"],
                        "networkMode": "awsvpc",
                        "runtimePlatform": {"cpuArchitecture": "ARM64"},
                        "containerDefinitions": [
                            {
                                "name": "backend",
                                "environment": [
                                    {"name": "MIRIYUM_STORE_SEARCH_LLM_ENABLED", "value": "false"}
                                ],
                                "secrets": [],
                            }
                        ],
                    },
            )

            self.assertEqual([], validate(contract, task_definition))

    def test_requires_openai_parameter_secret_when_llm_is_enabled(self):
        validate = load_validator()

        with tempfile.TemporaryDirectory() as directory:
            contract = self.write_json(
                directory,
                "contract.json",
                {
                    "conditionalParameterSecrets": {
                        "MIRIYUM_STORE_SEARCH_LLM_ENABLED": ["OPENAI_API_KEY"]
                    },
                    "parameterReferencePaths": {
                        "OPENAI_API_KEY": "miriyum/shared/openai-api-key"
                    },
                },
            )
            task_definition = self.write_json(
                directory,
                "task-definition.json",
                {
                    "requiresCompatibilities": ["FARGATE"],
                    "networkMode": "awsvpc",
                    "runtimePlatform": {"cpuArchitecture": "ARM64"},
                    "containerDefinitions": [
                        {
                            "name": "backend",
                            "environment": [
                                {"name": "MIRIYUM_STORE_SEARCH_LLM_ENABLED", "value": "true"}
                            ],
                            "secrets": [
                                {
                                    "name": "OPENAI_API_KEY",
                                    "valueFrom": (
                                        "arn:aws:ssm:ap-northeast-2:123456789012:"
                                        "parameter/miriyum/shared/openai-api-key"
                                    ),
                                }
                            ],
                        }
                    ],
                },
            )

            self.assertEqual([], validate(contract, task_definition))

    def test_rejects_invalid_openai_parameter_placeholder(self):
        validate = load_validator()

        with tempfile.TemporaryDirectory() as directory:
            contract = self.write_json(
                directory,
                "contract.json",
                {
                    "parameterReferencePaths": {
                        "OPENAI_API_KEY": "miriyum/shared/openai-api-key"
                    }
                },
            )
            task_definition = self.write_json(
                directory,
                "task-definition.json",
                {
                    "requiresCompatibilities": ["FARGATE"],
                    "networkMode": "awsvpc",
                    "runtimePlatform": {"cpuArchitecture": "ARM64"},
                    "containerDefinitions": [
                        {
                            "name": "backend",
                            "secrets": [
                                {
                                    "name": "OPENAI_API_KEY",
                                    "valueFrom": "REPLACE_WITH_WRONG_PARAMETER_ARN",
                                }
                            ],
                        }
                    ],
                },
            )

            self.assertEqual(
                ["Parameter reference does not match contract path: OPENAI_API_KEY"],
                validate(contract, task_definition),
            )

    def test_rejects_openai_parameter_secret_when_llm_is_disabled(self):
        validate = load_validator()

        with tempfile.TemporaryDirectory() as directory:
            contract = self.write_json(
                directory,
                "contract.json",
                {
                    "conditionalParameterSecrets": {
                        "MIRIYUM_STORE_SEARCH_LLM_ENABLED": ["OPENAI_API_KEY"]
                    },
                    "parameterReferencePaths": {
                        "OPENAI_API_KEY": "miriyum/shared/openai-api-key"
                    },
                },
            )
            task_definition = self.write_json(
                directory,
                "task-definition.json",
                {
                    "requiresCompatibilities": ["FARGATE"],
                    "networkMode": "awsvpc",
                    "runtimePlatform": {"cpuArchitecture": "ARM64"},
                    "containerDefinitions": [
                        {
                            "name": "backend",
                            "environment": [
                                {"name": "MIRIYUM_STORE_SEARCH_LLM_ENABLED", "value": "false"}
                            ],
                            "secrets": [
                                {
                                    "name": "OPENAI_API_KEY",
                                    "valueFrom": "REPLACE_WITH_OPENAI_API_KEY_PARAMETER_ARN",
                                }
                            ],
                        }
                    ],
                },
            )

            self.assertEqual(
                [
                    "Conditional parameter secret must be absent when disabled: "
                    "OPENAI_API_KEY"
                ],
                validate(contract, task_definition),
            )

    def test_rejects_parameter_reference_that_uses_a_different_path(self):
        validate = load_validator()

        with tempfile.TemporaryDirectory() as directory:
            contract = self.write_json(
                    directory,
                    "contract.json",
                    {
                        "parameterSecrets": ["OPENAI_API_KEY"],
                        "parameterReferencePaths": {
                            "OPENAI_API_KEY": "miriyum/shared/openai-api-key"
                        },
                    },
            )
            task_definition = self.write_json(
                    directory,
                    "task-definition.json",
                    {
                        "requiresCompatibilities": ["FARGATE"],
                        "networkMode": "awsvpc",
                        "runtimePlatform": {"cpuArchitecture": "ARM64"},
                        "containerDefinitions": [
                            {
                                "name": "backend",
                                "secrets": [
                                    {
                                        "name": "OPENAI_API_KEY",
                                        "valueFrom": (
                                            "arn:aws:ssm:ap-northeast-2:"
                                            "123456789012:parameter/miriyum/other-key"
                                        ),
                                    }
                                ],
                            }
                        ],
                    },
            )

            self.assertEqual(
                    [
                        "Parameter reference does not match contract path: "
                        "OPENAI_API_KEY"
                    ],
                    validate(contract, task_definition),
            )

    def test_production_task_definition_enables_valkey_tls(self):
        task_definition = json.loads(
                Path("deploy/ecs/production-task-definition.json").read_text(encoding="utf-8"))
        environment = {
                item["name"]: item["value"]
                for item in task_definition["containerDefinitions"][0]["environment"]
        }

        self.assertEqual("true", environment["MIRIYUM_VALKEY_SSL_ENABLED"])

    def test_production_task_definition_keeps_waiting_auto_open_disabled_by_default(self):
        task_definition = json.loads(
                Path("deploy/ecs/production-task-definition.json").read_text(encoding="utf-8"))
        environment = {
                item["name"]: item["value"]
                for item in task_definition["containerDefinitions"][0]["environment"]
        }

        self.assertEqual("false", environment["MIRIYUM_WAITING_AUTO_OPEN_ENABLED"])
        self.assertEqual("", environment["MIRIYUM_WAITING_AUTO_OPEN_WORKER_ID"])
        self.assertEqual("PT0S", environment["MIRIYUM_WAITING_AUTO_OPEN_PLANNING_HORIZON"])
        self.assertEqual("0", environment["MIRIYUM_WAITING_AUTO_OPEN_PLANNING_BATCH_SIZE"])
        self.assertEqual("0", environment["MIRIYUM_WAITING_AUTO_OPEN_CLAIM_BATCH_SIZE"])
        self.assertEqual("PT0S", environment["MIRIYUM_WAITING_AUTO_OPEN_LEASE_DURATION"])
        self.assertEqual("0", environment["MIRIYUM_WAITING_AUTO_OPEN_MAX_ATTEMPTS"])
        self.assertEqual("PT0S", environment["MIRIYUM_WAITING_AUTO_OPEN_INITIAL_RETRY_DELAY"])
        self.assertEqual("PT0S", environment["MIRIYUM_WAITING_AUTO_OPEN_MAXIMUM_RETRY_DELAY"])
        self.assertEqual("0", environment["MIRIYUM_WAITING_AUTO_OPEN_INVALIDATION_BATCH_SIZE"])
        self.assertEqual("PT0S", environment["MIRIYUM_WAITING_AUTO_OPEN_POLL_DELAY"])
        self.assertEqual("PT0S", environment["MIRIYUM_WAITING_AUTO_OPEN_INITIAL_DELAY"])

    def test_production_task_definition_uses_canonical_geocoding_secret(self):
        canonical_key = "MIRIYUM_STORE_GEOCODING_REST_API_KEY"
        legacy_key = "MIRIYUM_KAKAO_LOCAL_REST_API_KEY"
        contract = json.loads(
                Path("deploy/ecs/production-secret-contract.json").read_text(encoding="utf-8"))
        task_definition = json.loads(
                Path("deploy/ecs/production-task-definition.json").read_text(encoding="utf-8"))
        backend = next(
                container
                for container in task_definition["containerDefinitions"]
                if container["name"] == "backend"
        )
        secrets = {
                item["name"]: item["valueFrom"]
                for item in backend["secrets"]
        }

        self.assertIn(canonical_key, contract["requiredSecrets"])
        self.assertNotIn(legacy_key, contract["requiredSecrets"])
        self.assertIn(canonical_key, secrets)
        self.assertNotIn(legacy_key, secrets)
        self.assertTrue(secrets[canonical_key].endswith(f":{canonical_key}::"))

    def test_accepts_a_whole_runtime_config_secret_with_parameter_secrets(self):
        validate = load_validator()

        with tempfile.TemporaryDirectory() as directory:
            contract = self.write_json(
                    directory,
                    "contract.json",
                    {
                        "conditionalWholeSecrets": {
                            "MIRIYUM_RUNTIME_CONFIG_ENABLED": ["SPRING_APPLICATION_JSON"]
                        },
                        "wholeSecretReferences": {
                            "SPRING_APPLICATION_JSON": {
                                "secretName": "miriyum/production/backend-runtime-config",
                                "templatePlaceholder": "REPLACE_WITH_RUNTIME_CONFIG_SECRET_ARN",
                            }
                        },
                        "parameterSecrets": ["OPENAI_API_KEY"],
                        "parameterReferencePaths": {
                            "OPENAI_API_KEY": "miriyum/shared/openai-api-key"
                        },
                    },
            )
            task_definition = self.write_json(
                    directory,
                    "task-definition.json",
                    {
                        "requiresCompatibilities": ["FARGATE"],
                        "networkMode": "awsvpc",
                        "runtimePlatform": {"cpuArchitecture": "ARM64"},
                        "containerDefinitions": [{
                            "name": "backend",
                            "environment": [
                                {"name": "MIRIYUM_RUNTIME_CONFIG_ENABLED", "value": "true"}
                            ],
                            "secrets": [
                                {
                                    "name": "SPRING_APPLICATION_JSON",
                                    "valueFrom": (
                                        "arn:aws:secretsmanager:ap-northeast-2:123456789012:"
                                        "secret:miriyum/production/backend-runtime-config-abcdef"
                                    ),
                                },
                                {
                                    "name": "OPENAI_API_KEY",
                                    "valueFrom": (
                                        "arn:aws:ssm:ap-northeast-2:123456789012:"
                                        "parameter/miriyum/shared/openai-api-key"
                                    ),
                                },
                            ],
                        }],
                    },
            )

            self.assertEqual([], validate(contract, task_definition))

    def test_rejects_a_whole_runtime_config_secret_that_selects_a_json_key(self):
        validate = load_validator()

        with tempfile.TemporaryDirectory() as directory:
            contract = self.write_json(
                    directory,
                    "contract.json",
                    {
                        "wholeSecrets": ["SPRING_APPLICATION_JSON"],
                        "wholeSecretReferences": {
                            "SPRING_APPLICATION_JSON": {
                                "secretName": "miriyum/production/backend-runtime-config",
                                "templatePlaceholder": "REPLACE_WITH_RUNTIME_CONFIG_SECRET_ARN",
                            }
                        },
                    },
            )
            task_definition = self.write_json(
                    directory,
                    "task-definition.json",
                    {
                        "requiresCompatibilities": ["FARGATE"],
                        "networkMode": "awsvpc",
                        "runtimePlatform": {"cpuArchitecture": "ARM64"},
                        "containerDefinitions": [{
                            "name": "backend",
                            "secrets": [{
                                "name": "SPRING_APPLICATION_JSON",
                                "valueFrom": "APPLICATION:SPRING_APPLICATION_JSON::",
                            }],
                        }],
                    },
            )

            self.assertEqual(
                    ["Whole secret reference must not select a JSON key: SPRING_APPLICATION_JSON"],
                    validate(contract, task_definition),
            )

    def test_rejects_runtime_config_secret_when_the_flag_is_disabled(self):
        validate = load_validator()

        with tempfile.TemporaryDirectory() as directory:
            contract = self.write_json(
                    directory,
                    "contract.json",
                    {
                        "conditionalWholeSecrets": {
                            "MIRIYUM_RUNTIME_CONFIG_ENABLED": ["SPRING_APPLICATION_JSON"]
                        },
                        "wholeSecretReferences": {
                            "SPRING_APPLICATION_JSON": {
                                "secretName": "miriyum/production/backend-runtime-config",
                                "templatePlaceholder": "REPLACE_WITH_RUNTIME_CONFIG_SECRET_ARN",
                            }
                        },
                    },
            )
            task_definition = self.write_json(
                    directory,
                    "task-definition.json",
                    {
                        "requiresCompatibilities": ["FARGATE"],
                        "networkMode": "awsvpc",
                        "runtimePlatform": {"cpuArchitecture": "ARM64"},
                        "containerDefinitions": [{
                            "name": "backend",
                            "environment": [
                                {"name": "MIRIYUM_RUNTIME_CONFIG_ENABLED", "value": "false"}
                            ],
                            "secrets": [{
                                "name": "SPRING_APPLICATION_JSON",
                                "valueFrom": "REPLACE_WITH_RUNTIME_CONFIG_SECRET_ARN",
                            }],
                        }],
                    },
            )

            self.assertEqual(
                    ["Conditional whole secret must be absent when disabled: SPRING_APPLICATION_JSON"],
                    validate(contract, task_definition),
            )


if __name__ == "__main__":
    unittest.main()
