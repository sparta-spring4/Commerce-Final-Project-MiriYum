param(
    [string]$WorkflowPath = (Join-Path $PSScriptRoot "..\.github\workflows\backend-cd.yml"),
    [string]$ComposePath = (Join-Path $PSScriptRoot "..\deploy\docker-compose.prod.yml"),
    [string]$ComposeEnvPath = (Join-Path $PSScriptRoot "..\deploy\.env.example")
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
    'deployments?environment=$BACKEND_DEPLOYMENT_ENVIRONMENT&per_page=100',
    "last_deployed_sha",
    'git diff --name-only "$last_deployed_sha" "$WORKFLOW_SHA"',
    "git hash-object -t tree /dev/null",
    "id: ecr-image",
    "id: frontend-image",
    "Build and push ARM64 frontend image",
    '${IMAGE_TAG}-frontend',
    "FRONTEND_IMAGE='`$frontend_image_uri'",
    "Frontend CI did not complete successfully",
    "steps.ecr-image.outputs.exists != 'true'",
    "Manual deployment requires an existing immutable ECR image tag",
    'ref: ${{ inputs.image_tag }}',
    "retry-max-attempts: 2",
    "BACKEND_DEPLOYMENT_ENVIRONMENT: staging-backend",
    "deployments: write",
    "Record backend deployment marker",
    '--arg ref "$IMAGE_TAG"',
    '--field environment="$BACKEND_DEPLOYMENT_ENVIRONMENT"',
    'if .sha != $image_tag then',
    'Deployment response SHA does not match selected image tag',
    "Mark backend deployment successful",
    "Mark backend deployment failed",
    "deploy/monitoring/cloudwatch-agent-config.json",
    "cloudwatch_base64",
    "/opt/miriyum/monitoring/cloudwatch-agent.json",
    "amazon-cloudwatch-agent-ctl -a fetch-config",
    "file:/opt/miriyum/monitoring/cloudwatch-agent.json",
    "deploy/nginx/templates/snippets/sse-location.conf",
    "nginx_sse_base64",
    "/opt/miriyum/nginx/templates/snippets/sse-location.conf"
)

foreach ($fragment in $requiredFragments) {
    if (-not $workflow.Contains($fragment)) {
        throw "Missing CD retry safeguard: $fragment"
    }
}

$cursorSecretName = "MIRIYUM_NOTIFICATION_HISTORY_CURSOR_SECRET"
$cursorSecret = "test-only-notification-history-cursor-secret"
$previousCursorSecret = [Environment]::GetEnvironmentVariable($cursorSecretName, "Process")

try {
    [Environment]::SetEnvironmentVariable($cursorSecretName, $cursorSecret, "Process")
    $composeJson = & docker compose --env-file $ComposeEnvPath -f $ComposePath config --format json

    if ($LASTEXITCODE -ne 0) {
        throw "Failed to render production Docker Compose configuration."
    }

    $compose = ($composeJson -join "`n") | ConvertFrom-Json
    $renderedCursorSecret = $compose.services.backend.environment.$cursorSecretName

    if ($renderedCursorSecret -ne $cursorSecret) {
        throw "Production backend does not receive $cursorSecretName."
    }
}
finally {
    [Environment]::SetEnvironmentVariable($cursorSecretName, $previousCursorSecret, "Process")
}

Write-Output "Backend CD and production Compose safeguards are configured."
