[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$terraformDir = Split-Path -Parent $PSScriptRoot

Write-Host "This deletes the production ECS service, ALB, NAT Gateway, EIP, and api.miriyum.click record. RDS data is retained and stopped afterward." -ForegroundColor Yellow
if ((Read-Host "Type DOWN to continue") -cne "DOWN") {
  Write-Host "Cancelled."
  exit 0
}

Push-Location $terraformDir
try {
  terraform apply -var production_infrastructure_enabled=false -var production_backend_desired_count=0
  if ($LASTEXITCODE -ne 0) { throw "Terraform apply failed." }
}
finally {
  Pop-Location
}

aws rds stop-db-instance --db-instance-identifier miriyum-prod-mysql | Out-Null
if ($LASTEXITCODE -ne 0) { throw "RDS stop request failed." }

do {
  Start-Sleep -Seconds 15
  $rdsStatus = aws rds describe-db-instances `
    --db-instance-identifier miriyum-prod-mysql `
    --query "DBInstances[0].DBInstanceStatus" `
    --output text
  if ($LASTEXITCODE -ne 0) { throw "RDS status check failed." }
  Write-Host "RDS status: $rdsStatus"
} while ($rdsStatus -eq "stopping")

if ($rdsStatus -ne "stopped") { throw "RDS did not reach stopped state: $rdsStatus" }
Write-Host "Production is down. RDS is stopped; stored data and backups remain." -ForegroundColor Green
