param(
    [string]$WorkflowPath = (Join-Path $PSScriptRoot "..\.github\workflows\backend-cd.yml"),
    [string]$FrontendDockerfilePath = (Join-Path $PSScriptRoot "..\frontend\Dockerfile"),
    [string]$ComposePath = (Join-Path $PSScriptRoot "..\deploy\docker-compose.prod.yml"),
    [string]$ComposeEnvPath = (Join-Path $PSScriptRoot "..\deploy\.env.example")
)

$workflow = Get-Content -Raw -Path $WorkflowPath
$frontendDockerfile = Get-Content -Raw -Path $FrontendDockerfilePath
$composeSource = Get-Content -Raw -Path $ComposePath
$composeEnvironmentExample = Get-Content -Raw -Path $ComposeEnvPath

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
    "Manual or reusable deployment requires an existing immutable ECR image tag",
    'ref: ${{ steps.image.outputs.tag }}',
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
    "NOTIFICATION_READ_MINIMUM_COMPATIBLE_SHA: 515531e122ebbce13d8eead4a3ff15a94c25e0b3"
    "Verify notification read minimum compatible writer revision"
    "github.workflow_sha"
    "Preserve trusted notification read deployment controls"
    '$RUNNER_TEMP/notification-read-deployment-gate.py'
    '$RUNNER_TEMP/notification-read-deploy.sh'
    "fetch-depth: 0"
)

foreach ($fragment in $requiredFragments) {
    if (-not $workflow.Contains($fragment)) {
        throw "Missing CD retry safeguard: $fragment"
    }
}

if ($workflow.Contains('script_base64=$(base64 --wrap=0 deploy/deploy.sh)')) {
    throw "Staging CD must upload the trusted deploy script, not the selected candidate copy."
}

$frontendBuildArgumentsMatch = [regex]::Match(
    $workflow,
    '(?m)^[ ]{10}build-args:[ ]*\|[ ]*\r?\n(?<arguments>(?:^[ ]{12}\S.*\r?\n)+)'
)
if (-not $frontendBuildArgumentsMatch.Success) {
    throw "Frontend Docker build arguments were not found."
}

$frontendBuildArguments = @(
    $frontendBuildArgumentsMatch.Groups['arguments'].Value -split '\r?\n' |
        ForEach-Object { $_.Trim() } |
        Where-Object { $_ }
)
$allowedFrontendBuildArguments = @(
    'VITE_KAKAO_MAP_APP_KEY=${{ vars.STAGING_KAKAO_MAP_APP_KEY }}',
    'MIRIYUM_PORTONE_STORE_ID=${{ vars.MIRIYUM_PORTONE_STORE_ID }}',
    'MIRIYUM_PORTONE_CHANNEL_KEY=${{ vars.MIRIYUM_PORTONE_CHANNEL_KEY }}'
)
$unexpectedFrontendBuildArguments = @(
    Compare-Object $allowedFrontendBuildArguments $frontendBuildArguments
)
if (
    $frontendBuildArguments.Count -ne $allowedFrontendBuildArguments.Count -or
    $unexpectedFrontendBuildArguments.Count -ne 0
) {
    throw "Frontend Docker build arguments do not match the public-value allowlist."
}

$publicPortOneNames = @(
    'MIRIYUM_PORTONE_STORE_ID',
    'MIRIYUM_PORTONE_CHANNEL_KEY'
)
$serverPortOneSecretNames = @(
    'MIRIYUM_PORTONE_API_SECRET',
    'MIRIYUM_PAYMENT_CURSOR_SECRET',
    'MIRIYUM_PORTONE_WEBHOOK_SECRET'
)
$allPortOneNames = @($publicPortOneNames + $serverPortOneSecretNames)

foreach ($name in $publicPortOneNames) {
    $escapedName = [regex]::Escape($name)
    $environmentPattern = '(?m)^ENV ' + $escapedName + '=\$\{' + $escapedName + '\}\r?$'
    $argumentCount = [regex]::Matches(
        $frontendDockerfile,
        "(?m)^ARG $escapedName\r?$"
    ).Count
    $environmentCount = [regex]::Matches(
        $frontendDockerfile,
        $environmentPattern
    ).Count

    if ($argumentCount -ne 1 -or $environmentCount -ne 1) {
        throw "Frontend Dockerfile must expose $name exactly once as ARG and ENV."
    }
}

foreach ($name in $serverPortOneSecretNames) {
    if (
        $workflow.Contains($name) -or
        $frontendDockerfile.Contains($name)
    ) {
        throw "$name must not be a Frontend build argument."
    }
}

foreach ($name in @('MIRIYUM_PORTONE_STORE_ID') + $serverPortOneSecretNames) {
    $composeKeyCount = [regex]::Matches(
        $composeSource,
        "(?m)^\s+$([regex]::Escape($name)):\s*"
    ).Count
    if ($composeKeyCount -ne 1) {
        throw "Production Compose must declare $name exactly once."
    }

    $emptyExampleCount = [regex]::Matches(
        $composeEnvironmentExample,
        "(?m)^$([regex]::Escape($name))=\r?$"
    ).Count
    if ($emptyExampleCount -ne 1) {
        throw "Production environment example must declare $name exactly once with an empty value."
    }
}

if (
    $composeSource.Contains('MIRIYUM_PORTONE_CHANNEL_KEY') -or
    $composeEnvironmentExample.Contains('MIRIYUM_PORTONE_CHANNEL_KEY')
) {
    throw "PortOne Channel Key must not be passed to a Compose service."
}

$cursorSecrets = [ordered]@{
    MIRIYUM_NOTIFICATION_HISTORY_CURSOR_SECRET = "test-only-notification-history-cursor-secret"
    MIRIYUM_WAITING_HISTORY_CURSOR_SECRET = "test-only-waiting-history-cursor-secret"
}
$portOneStoreId = 'test-only-portone-store-id'
$portOneSecrets = [ordered]@{
    MIRIYUM_PORTONE_API_SECRET = 'test-only-portone-api-secret'
    MIRIYUM_PAYMENT_CURSOR_SECRET = 'test-only-payment-cursor-secret'
    MIRIYUM_PORTONE_WEBHOOK_SECRET = 'test-only-portone-webhook-secret'
}
$testEnvironment = @{
    MIRIYUM_PORTONE_STORE_ID = $portOneStoreId
    MIRIYUM_PAYMENT_ENABLED = 'true'
}
foreach ($entry in $portOneSecrets.GetEnumerator()) {
    $testEnvironment[$entry.Key] = $entry.Value
}
foreach ($entry in $cursorSecrets.GetEnumerator()) {
    $testEnvironment[$entry.Key] = $entry.Value
}
$previousEnvironment = @{}

try {
    foreach ($entry in $testEnvironment.GetEnumerator()) {
        $previousEnvironment[$entry.Key] = [Environment]::GetEnvironmentVariable($entry.Key, "Process")
        [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, "Process")
    }

    $composeJson = & docker compose --env-file $ComposeEnvPath -f $ComposePath config --format json

    if ($LASTEXITCODE -ne 0) {
        throw "Failed to render production Docker Compose configuration."
    }

    $compose = ($composeJson -join "`n") | ConvertFrom-Json
    foreach ($entry in $cursorSecrets.GetEnumerator()) {
        $renderedCursorSecret = $compose.services.backend.environment.($entry.Key)
        if ($renderedCursorSecret -ne $entry.Value) {
            throw "Production backend does not receive $($entry.Key)."
        }
    }

    if ($compose.services.backend.environment.MIRIYUM_PORTONE_STORE_ID -ne $portOneStoreId) {
        throw "Production backend does not receive MIRIYUM_PORTONE_STORE_ID."
    }
    foreach ($entry in $portOneSecrets.GetEnumerator()) {
        if ($compose.services.backend.environment.($entry.Key) -ne $entry.Value) {
            throw "Production backend does not receive $($entry.Key)."
        }
    }
    foreach ($serviceProperty in $compose.services.PSObject.Properties) {
        if ($serviceProperty.Name -eq 'backend') {
            continue
        }

        $serviceEnvironment = $serviceProperty.Value.environment
        foreach ($name in $cursorSecrets.Keys) {
            if ($null -ne $serviceEnvironment -and $null -ne $serviceEnvironment.PSObject.Properties[$name]) {
                throw "Compose service $($serviceProperty.Name) must not receive $name."
            }
        }
        foreach ($name in $allPortOneNames) {
            if ($null -ne $serviceEnvironment -and $null -ne $serviceEnvironment.PSObject.Properties[$name]) {
                throw "Compose service $($serviceProperty.Name) must not receive $name."
            }
        }
    }
}
finally {
    foreach ($entry in $previousEnvironment.GetEnumerator()) {
        [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, "Process")
    }
}

Write-Output "Backend CD and production Compose safeguards are configured."
