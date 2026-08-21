import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github/workflows/backend-production-ecs-cd.yml"
TASK_DEFINITION = ROOT / "deploy/ecs/production-task-definition.json"
TERRAFORM = ROOT / "infra/terraform/production/storage-s3.tf"


class ProductionS3StorageContractTest(unittest.TestCase):
    def test_bucket_policy_requires_tls_and_iam_allows_only_image_prefixes(self):
        terraform = TERRAFORM.read_text(encoding="utf-8")

        self.assertIn('"${aws_s3_bucket.production_files.arn}/public/stores/*"', terraform)
        self.assertIn('"${aws_s3_bucket.production_files.arn}/public/menus/*"', terraform)
        self.assertIn('resource "aws_s3_bucket_policy" "production_files_tls_only"', terraform)
        self.assertIn('variable = "aws:SecureTransport"', terraform)
        self.assertIn('values   = ["false"]', terraform)

    def test_lifecycle_only_aborts_incomplete_uploads(self):
        terraform = TERRAFORM.read_text(encoding="utf-8")

        self.assertIn('resource "aws_s3_bucket_lifecycle_configuration" "production_files"', terraform)
        self.assertIn("abort_incomplete_multipart_upload", terraform)
        self.assertIn("days_after_initiation = 7", terraform)
        self.assertNotIn("expiration {", terraform)

    def test_task_definition_starts_with_s3_and_reconciliation_disabled(self):
        task_definition = json.loads(TASK_DEFINITION.read_text(encoding="utf-8"))
        backend = next(item for item in task_definition["containerDefinitions"] if item["name"] == "backend")
        environment = {item["name"]: item["value"] for item in backend["environment"]}
        secret_names = {item["name"] for item in backend["secrets"]}

        self.assertEqual("false", environment["MIRIYUM_STORAGE_S3_ENABLED"])
        self.assertEqual("false", environment["MIRIYUM_STORAGE_S3_RECONCILIATION_ENABLED"])
        self.assertNotIn("MIRIYUM_STORAGE_S3_BUCKET", secret_names)

    def test_cd_preserves_disabled_default_and_injects_bucket_only_when_enabled(self):
        workflow = WORKFLOW.read_text(encoding="utf-8")

        self.assertIn('select(.name == "MIRIYUM_STORAGE_S3_ENABLED") | .value][0] // "false"', workflow)
        self.assertIn('select(.name == "MIRIYUM_STORAGE_S3_RECONCILIATION_ENABLED") | .value][0] // "false"', workflow)
        self.assertIn('if $storage_s3_enabled == "true" then', workflow)
        self.assertNotIn('{name: "MIRIYUM_STORAGE_S3_ENABLED", value: "true"}', workflow)


if __name__ == "__main__":
    unittest.main()
