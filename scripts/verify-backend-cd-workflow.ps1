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
    "WORKFLOW_SHA^1",
    "scripts/should-deploy-backend.py",
    'deployable=$deployable',
    "Skipping staging CD because the revision has no backend deployment input changes.",
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
