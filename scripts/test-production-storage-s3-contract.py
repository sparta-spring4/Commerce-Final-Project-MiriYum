import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github/workflows/backend-production-ecs-cd.yml"
TASK_DEFINITION = ROOT / "deploy/ecs/production-task-definition.json"
TERRAFORM = ROOT / "infra/terraform/production/storage-s3.tf"
PREFLIGHT = ROOT / "scripts/verify-production-storage-s3-preflight.py"


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

    def test_task_definition_enables_s3_and_reconciliation_with_bucket_parameter(self):
        task_definition = json.loads(TASK_DEFINITION.read_text(encoding="utf-8"))
        backend = next(item for item in task_definition["containerDefinitions"] if item["name"] == "backend")
        environment = {item["name"]: item["value"] for item in backend["environment"]}
        secret_names = {item["name"] for item in backend["secrets"]}

        self.assertEqual("true", environment["MIRIYUM_STORAGE_S3_ENABLED"])
        self.assertEqual("true", environment["MIRIYUM_STORAGE_S3_RECONCILIATION_ENABLED"])
        self.assertIn("MIRIYUM_STORAGE_S3_BUCKET", secret_names)

    def test_cd_requires_explicit_s3_activation_and_preflight(self):
        workflow = WORKFLOW.read_text(encoding="utf-8")

        self.assertIn('MIRIYUM_STORAGE_S3_ENABLED: ${{ vars.MIRIYUM_STORAGE_S3_ENABLED }}', workflow)
        self.assertIn('MIRIYUM_STORAGE_S3_RECONCILIATION_ENABLED: ${{ vars.MIRIYUM_STORAGE_S3_RECONCILIATION_ENABLED }}', workflow)
        self.assertIn('storage_s3_enabled="$MIRIYUM_STORAGE_S3_ENABLED"', workflow)
        self.assertIn('storage_s3_reconciliation_enabled="$MIRIYUM_STORAGE_S3_RECONCILIATION_ENABLED"', workflow)
        self.assertIn('aws ssm get-parameter', workflow)
        self.assertIn('MIRIYUM_STORAGE_S3_ENABLED and MIRIYUM_STORAGE_S3_RECONCILIATION_ENABLED must match.', workflow)
        self.assertIn('if $storage_s3_enabled == "true" then', workflow)
        self.assertIn('{name: "MIRIYUM_STORAGE_S3_ENABLED", value: $storage_s3_enabled}', workflow)

    def test_preflight_rejects_parameter_bucket_drift_wrong_prefix_and_missing_ssm_policy(self):
        account_id = "579750808837"
        region = "ap-northeast-2"
        bucket = f"miriyum-production-files-{account_id}-{region}"
        valid = {
            "parameter": {"Value": bucket},
            "bucket": {
                "location": {"LocationConstraint": region},
                "versioning": {},
                "encryption": {"ServerSideEncryptionConfiguration": {"Rules": [{"ApplyServerSideEncryptionByDefault": {"SSEAlgorithm": "AES256"}}]}},
                "public_access": {"PublicAccessBlockConfiguration": {"BlockPublicAcls": True, "IgnorePublicAcls": True, "BlockPublicPolicy": True, "RestrictPublicBuckets": True}},
                "policy": {"Policy": json.dumps({"Statement": [{"Sid": "DenyInsecureTransport", "Effect": "Deny", "Action": "s3:*", "Resource": [f"arn:aws:s3:::{bucket}", f"arn:aws:s3:::{bucket}/*"], "Condition": {"Bool": {"aws:SecureTransport": "false"}}}]})},
            },
            "task_policy": {"Statement": [
                {"Sid": "ReadBucketVersioning", "Effect": "Allow", "Action": ["s3:GetBucketVersioning"], "Resource": f"arn:aws:s3:::{bucket}"},
                {"Sid": "ManagePublicImageObjects", "Effect": "Allow", "Action": ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"], "Resource": [f"arn:aws:s3:::{bucket}/public/stores/*", f"arn:aws:s3:::{bucket}/public/menus/*"]},
            ]},
            "execution_policy": {"Statement": [{"Sid": "ReadProductionStorageBucketParameter", "Effect": "Allow", "Action": ["ssm:GetParameters"], "Resource": f"arn:aws:ssm:{region}:{account_id}:parameter/miriyum/production/storage-s3-bucket"}]},
        }

        with tempfile.TemporaryDirectory() as directory:
            directory_path = Path(directory)

            def run(payload):
                fixtures = {
                    "parameter": payload["parameter"],
                    "location": payload["bucket"]["location"],
                    "versioning": payload["bucket"]["versioning"],
                    "encryption": payload["bucket"]["encryption"],
                    "public_access": payload["bucket"]["public_access"],
                    "bucket_policy": payload["bucket"]["policy"],
                    "task_policy": payload["task_policy"],
                    "execution_policy": payload["execution_policy"],
                }
                paths = {}
                for name, value in fixtures.items():
                    path = directory_path / f"{name}.json"
                    path.write_text(json.dumps(value), encoding="utf-8")
                    paths[name] = path
                return subprocess.run([
                    sys.executable, str(PREFLIGHT), "--account-id", account_id, "--region", region,
                    "--parameter-json", str(paths["parameter"]), "--location-json", str(paths["location"]),
                    "--versioning-json", str(paths["versioning"]), "--encryption-json", str(paths["encryption"]),
                    "--public-access-json", str(paths["public_access"]), "--bucket-policy-json", str(paths["bucket_policy"]),
                    "--task-policy-json", str(paths["task_policy"]), "--execution-policy-json", str(paths["execution_policy"]),
                ], capture_output=True, text=True)

            self.assertEqual(0, run(valid).returncode)
            drift = json.loads(json.dumps(valid))
            drift["parameter"]["Value"] = "unexpected-bucket"
            self.assertNotEqual(0, run(drift).returncode)
            wrong_prefix = json.loads(json.dumps(valid))
            wrong_prefix["task_policy"]["Statement"][1]["Resource"][1] = f"arn:aws:s3:::{bucket}/public/other/*"
            self.assertNotEqual(0, run(wrong_prefix).returncode)
            missing_ssm = json.loads(json.dumps(valid))
            missing_ssm["execution_policy"]["Statement"][0]["Action"] = []
            self.assertNotEqual(0, run(missing_ssm).returncode)
            extra_action = json.loads(json.dumps(valid))
            extra_action["task_policy"]["Statement"][1]["Action"].append("s3:ListBucket")
            self.assertNotEqual(0, run(extra_action).returncode)
            extra_resource = json.loads(json.dumps(valid))
            extra_resource["task_policy"]["Statement"][1]["Resource"].append(
                f"arn:aws:s3:::{bucket}/public/other/*"
            )
            self.assertNotEqual(0, run(extra_resource).returncode)
            extra_allow_statement = json.loads(json.dumps(valid))
            extra_allow_statement["task_policy"]["Statement"].append(
                {
                    "Sid": "UnexpectedImageRead",
                    "Effect": "Allow",
                    "Action": "s3:GetObject",
                    "Resource": f"arn:aws:s3:::{bucket}/public/other/*",
                }
            )
            self.assertNotEqual(0, run(extra_allow_statement).returncode)


if __name__ == "__main__":
    unittest.main()
