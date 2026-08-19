[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$terraformDir = Split-Path -Parent $PSScriptRoot

aws rds start-db-instance --db-instance-identifier miriyum-prod-mysql | Out-Null
if ($LASTEXITCODE -ne 0) { throw "RDS start request failed." }
aws rds wait db-instance-available --db-instance-identifier miriyum-prod-mysql
if ($LASTEXITCODE -ne 0) { throw "RDS did not reach available state." }

$taskDefinition = aws ecs list-task-definitions `
  --family-prefix miriyum-production-backend `
  --status ACTIVE `
  --sort DESC `
  --max-results 1 `
  --query "taskDefinitionArns[0]" `
  --output text
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($taskDefinition) -or $taskDefinition -eq "None") {
  throw "No active miriyum-production-backend task definition was found."
}

Push-Location $terraformDir
try {
  terraform apply `
    -var production_infrastructure_enabled=true `
    -var production_backend_desired_count=1 `
    -var "production_backend_task_definition=$taskDefinition"
  if ($LASTEXITCODE -ne 0) { throw "Terraform apply failed." }
}
finally {
  Pop-Location
}

Write-Host "Production infrastructure is up. Confirm target health before using the API." -ForegroundColor Green
