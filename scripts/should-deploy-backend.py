import sys


DEPLOY_PATH_PREFIXES = ("backend/", "deploy/")
DEPLOY_WORKFLOW = ".github/workflows/backend-cd.yml"


def should_deploy(paths):
    return any(
        path.startswith(DEPLOY_PATH_PREFIXES) or path == DEPLOY_WORKFLOW
        for path in paths
    )


def main():
    paths = (line.strip() for line in sys.stdin)
    print("true" if should_deploy(path for path in paths if path) else "false")


if __name__ == "__main__":
    main()
