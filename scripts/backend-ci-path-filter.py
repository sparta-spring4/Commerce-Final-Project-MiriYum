import sys


FULL_TEST_PREFIXES = ("backend/",)
UNIT_TEST_PREFIXES = ("deploy/",)
SAFE_SKIP_PREFIXES = ("docs/", "frontend/", "performance/k6/")
SAFE_SKIP_PATHS = (
    ".github/workflows/backend-cd.yml",
    ".github/workflows/backend-production-ecs-cd.yml",
    ".github/workflows/document-routing.yml",
    ".github/workflows/frontend-ci.yml",
    ".github/workflows/k6-contract.yml",
)
FULL_TEST_PATHS = (
    ".github/workflows/backend-ci.yml",
    "deploy/deploy.sh",
)


def classify(paths):
    normalized_paths = [path.strip() for path in paths if path.strip()]
    if not normalized_paths:
        return "full"
    if any(path in FULL_TEST_PATHS or path.startswith(FULL_TEST_PREFIXES) for path in normalized_paths):
        return "full"
    if any(
        path.startswith("docs/specs/") and path.endswith("openapi.yaml")
        for path in normalized_paths
    ) or any(path.startswith(UNIT_TEST_PREFIXES) for path in normalized_paths):
        return "unit"
    if all(path in SAFE_SKIP_PATHS or path.startswith(SAFE_SKIP_PREFIXES) for path in normalized_paths):
        return "contract"
    return "full"


if __name__ == "__main__":
    print(classify(sys.stdin))
