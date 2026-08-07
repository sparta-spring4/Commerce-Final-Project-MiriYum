# Backend CD Path Filter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep Backend CI and its Required check unchanged while skipping automatic staging backend deployment for commits that do not change backend deployment inputs.

**Architecture:** `verify-source` will checkout the successful `workflow_run` revision, compare it with its first parent, and pass changed paths to a small Python classifier. The classifier returns `true` only for `backend/**`, `deploy/**`, or `.github/workflows/backend-cd.yml`; manual SHA deployment remains unconditional.

**Tech Stack:** GitHub Actions YAML, Bash, Python 3 standard library, PowerShell workflow contract verifier.

## Global Constraints

- Keep `backend-ci` as a Required check and do not add CI path filters.
- Keep stale revision checks and `backend-cd-staging` concurrency unchanged.
- Keep manual `workflow_dispatch` deployment for an existing full 40-character Git SHA.
- Do not change AWS IAM, ECR, SSM, EC2, Nginx, Docker Compose, or frontend workflows.
- Do not stage or commit the existing untracked `.idea/` directory.

---

### Task 1: Add the changed-path classifier

**Files:**
- Create: `scripts/should-deploy-backend.py`
- Create: `scripts/test-should-deploy-backend.py`

**Interfaces:**
- Consumes: newline-delimited repository-relative POSIX paths from standard input.
- Produces: one line, `true` or `false`, on standard output.

- [ ] **Step 1: Write the classifier tests**

Create standard-library `unittest` cases for backend, deploy, Backend CD workflow, docs-only, frontend-only, and empty input.

```python
class ShouldDeployBackendTest(unittest.TestCase):
    def assertDecision(self, paths, expected):
        result = subprocess.run(
            [sys.executable, "scripts/should-deploy-backend.py"],
            input="\\n".join(paths) + "\\n",
            text=True,
            capture_output=True,
            check=True,
        )
        self.assertEqual(expected, result.stdout.strip())

    def test_backend_change_deploys(self):
        self.assertDecision(["backend/src/main/App.java"], "true")

    def test_deploy_change_deploys(self):
        self.assertDecision(["deploy/docker-compose.prod.yml"], "true")

    def test_backend_cd_workflow_change_deploys(self):
        self.assertDecision([".github/workflows/backend-cd.yml"], "true")

    def test_docs_frontend_and_empty_changes_skip(self):
        self.assertDecision(["docs/README.md", "frontend/src/App.tsx"], "false")
        self.assertDecision([], "false")
```

- [ ] **Step 2: Run the new tests and verify they fail**

Run from the repository root:

```bash
python3 scripts/test-should-deploy-backend.py
```

Expected: FAIL because `scripts/should-deploy-backend.py` does not exist yet.

- [ ] **Step 3: Implement the minimal classifier**

Read stdin, discard blank lines, and return `true` when any path starts with `backend/` or `deploy/`, or exactly equals `.github/workflows/backend-cd.yml`. Do not inspect file contents or use Windows path separators.

- [ ] **Step 4: Run the tests and verify they pass**

Run:

```bash
python3 scripts/test-should-deploy-backend.py
```

Expected: all classifier cases pass.

### Task 2: Gate automatic Backend CD by changed paths

**Files:**
- Modify: `.github/workflows/backend-cd.yml` in `verify-source`

**Interfaces:**
- Consumes: `github.event.workflow_run.head_sha`, the checked-out revision, and the classifier output.
- Produces: the existing `deployable` job output consumed by the `deploy` job.

- [ ] **Step 1: Checkout the successful CI revision for automatic runs**

Add an `actions/checkout` step with the existing pinned action SHA, `ref: ${{ github.event.workflow_run.head_sha }}`, and `fetch-depth: 2`. Run it only for `workflow_run`; manual dispatch keeps its existing checkout in the deploy job.

- [ ] **Step 2: Compare the merge commit with its first parent**

In the existing source verification step, keep the stale revision check first. For automatic runs, calculate changed files with:

```bash
changed_files=$(git diff --name-only "$WORKFLOW_SHA^1" "$WORKFLOW_SHA")
deployable=$(printf '%s\\n' "$changed_files" | python3 scripts/should-deploy-backend.py)
echo "deployable=$deployable" >> "$GITHUB_OUTPUT"
```

For `workflow_dispatch`, write `deployable=true` without path filtering.

- [ ] **Step 3: Preserve skip and deploy behavior**

Keep the existing `deploy` job condition `needs.verify-source.outputs.deployable == 'true'`. Add a notice when an automatic docs/frontend-only revision is skipped. Do not move OIDC, ECR, SSM, health, stale revision, or concurrency steps.

- [ ] **Step 4: Run the workflow contract verifier**

Update `scripts/verify-backend-cd-workflow.ps1` to require the classifier path, first-parent diff, manual bypass, and output assignment. Run:

```powershell
pwsh -File scripts/verify-backend-cd-workflow.ps1
```

Expected: `Backend CD immutable ECR and OIDC safeguards are configured.`

### Task 3: Run the classifier in Backend CI

**Files:**
- Modify: `.github/workflows/backend-ci.yml` in the `backend-ci` aggregate job

**Interfaces:**
- Consumes: the repository checkout and the classifier test script.
- Produces: a failed `backend-ci` check if the path classifier contract regresses.

- [ ] **Step 1: Add the focused contract test step**

After the aggregate job checkout, run:

```yaml
- name: Verify backend CD path filter
  run: python3 scripts/test-should-deploy-backend.py
```

- [ ] **Step 2: Run the focused local verification**

Run:

```bash
python3 scripts/test-should-deploy-backend.py
pwsh -File scripts/verify-backend-cd-workflow.ps1
git diff --check
```

Expected: all commands exit successfully.

### Task 4: Review the final change boundary

**Files:**
- Review only: `.github/workflows/backend-cd.yml`, `.github/workflows/backend-ci.yml`, `scripts/should-deploy-backend.py`, `scripts/test-should-deploy-backend.py`, `scripts/verify-backend-cd-workflow.ps1`

- [ ] **Step 1: Inspect the diff and status**

Run:

```bash
git diff --stat
git status --short
```

Expected: only the five implementation/verification paths are changed; `.idea/` remains untracked and unstaged.

- [ ] **Step 2: Verify the existing CD safeguards remain present**

Run the PowerShell contract verifier and inspect the workflow diff for unchanged OIDC retry, immutable ECR lookup, stale revision, manual dispatch, SSM, health, and concurrency behavior.

- [ ] **Step 3: Commit the implementation separately from the design**

```bash
git add .github/workflows/backend-cd.yml .github/workflows/backend-ci.yml scripts/should-deploy-backend.py scripts/test-should-deploy-backend.py scripts/verify-backend-cd-workflow.ps1
git commit -m "ci(cd): skip staging deploy for non-backend changes"
```
