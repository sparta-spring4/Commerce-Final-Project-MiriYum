import argparse
import json
import sys
from pathlib import Path


def load_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def statements(policy):
    return policy.get("Statement", [])


def list_value(value):
    return value if isinstance(value, list) else [value]


def has_exact_allow_policy(policy, expected_statements):
    allow_statements = [
        statement for statement in statements(policy) if statement.get("Effect") == "Allow"
    ]
    if len(allow_statements) != len(expected_statements):
        return False

    for statement in allow_statements:
        sid = statement.get("Sid")
        expected = expected_statements.get(sid)
        if expected is None or set(statement) != {"Sid", "Effect", "Action", "Resource"}:
            return False
        actions, resources = expected
        if set(list_value(statement["Action"])) != set(actions):
            return False
        if set(list_value(statement["Resource"])) != set(resources):
            return False
    return True


def tls_only(policy, bucket_arn):
    for statement in statements(policy):
        if statement.get("Sid") != "DenyInsecureTransport" or statement.get("Effect") != "Deny":
            continue
        if statement.get("Action") != "s3:*":
            continue
        if set(list_value(statement.get("Resource", []))) != {bucket_arn, f"{bucket_arn}/*"}:
            continue
        if statement.get("Condition", {}).get("Bool", {}).get("aws:SecureTransport") == "false":
            return True
    return False


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--account-id", required=True)
    parser.add_argument("--region", required=True)
    parser.add_argument("--parameter-json", required=True)
    parser.add_argument("--location-json", required=True)
    parser.add_argument("--versioning-json", required=True)
    parser.add_argument("--encryption-json", required=True)
    parser.add_argument("--public-access-json", required=True)
    parser.add_argument("--bucket-policy-json", required=True)
    parser.add_argument("--task-policy-json", required=True)
    parser.add_argument("--execution-policy-json", required=True)
    args = parser.parse_args()

    expected_bucket = f"miriyum-production-files-{args.account_id}-{args.region}"
    bucket_arn = f"arn:aws:s3:::{expected_bucket}"
    parameter_arn = f"arn:aws:ssm:{args.region}:{args.account_id}:parameter/miriyum/production/storage-s3-bucket"
    parameter = load_json(args.parameter_json)
    location = load_json(args.location_json)
    versioning = load_json(args.versioning_json)
    encryption = load_json(args.encryption_json)
    public_access = load_json(args.public_access_json).get("PublicAccessBlockConfiguration", {})
    bucket_policy = load_json(args.bucket_policy_json)
    bucket_policy = json.loads(bucket_policy["Policy"]) if isinstance(bucket_policy.get("Policy"), str) else bucket_policy
    task_policy = load_json(args.task_policy_json)
    execution_policy = load_json(args.execution_policy_json)

    valid = [
        parameter.get("Value") == expected_bucket,
        location.get("LocationConstraint") == args.region,
        not versioning.get("Status"),
        encryption.get("ServerSideEncryptionConfiguration", {}).get("Rules", [{}])[0]
        .get("ApplyServerSideEncryptionByDefault", {}).get("SSEAlgorithm") == "AES256",
        all(public_access.get(name) is True for name in ["BlockPublicAcls", "IgnorePublicAcls", "BlockPublicPolicy", "RestrictPublicBuckets"]),
        tls_only(bucket_policy, bucket_arn),
        has_exact_allow_policy(
            task_policy,
            {
                "ReadBucketVersioning": (["s3:GetBucketVersioning"], [bucket_arn]),
                "ManagePublicImageObjects": (
                    ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"],
                    [f"{bucket_arn}/public/stores/*", f"{bucket_arn}/public/menus/*"],
                ),
            },
        ),
        has_exact_allow_policy(
            execution_policy,
            {"ReadProductionStorageBucketParameter": (["ssm:GetParameters"], [parameter_arn])},
        ),
    ]
    if not all(valid):
        print("Production S3 activation preflight failed.", file=sys.stderr)
        return 1
    print("Production S3 activation preflight passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
