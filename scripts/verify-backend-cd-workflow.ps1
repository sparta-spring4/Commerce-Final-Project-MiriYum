param(
    [string]$WorkflowPath = (Join-Path $PSScriptRoot "..\.github\workflows\backend-cd.yml")
)

$workflow = Get-Content -Raw -Path $WorkflowPath

$requiredFragments = @(
    "- name: Check immutable ECR image exists",
    "aws ecr batch-get-image",
    "ecr:BatchGetImage",
    "Checkout automatic deployment revision",
    "git diff --name-only",
    "scripts/should-deploy-backend.py",
    'deployable=$deployable',
    "Skipping staging CD because the revision has no backend deployment input changes.",
    "deployments?environment=staging&per_page=100",
    "last_deployed_sha",
    'git diff --name-only "$last_deployed_sha" "$WORKFLOW_SHA"',
    "git hash-object -t tree /dev/null",
    "id: ecr-image",
    "steps.ecr-image.outputs.exists != 'true'",
    "Manual deployment requires an existing immutable ECR image tag",
    'ref: ${{ inputs.image_tag }}',
    "retry-max-attempts: 2"
)

foreach ($fragment in $requiredFragments) {
    if (-not $workflow.Contains($fragment)) {
        throw "Missing CD retry safeguard: $fragment"
    }
}

Write-Output "Backend CD immutable ECR and OIDC safeguards are configured."
