[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$ExpectedAccountId = "579750808837"
$Region = "ap-northeast-2"
$Cluster = "miriyum-prod-cluster"
$Service = "miriyum-prod-backend-service"
$TaskFamily = "miriyum-production-backend"
$Database = "miriyum-prod-mysql"
$TimeoutSeconds = 900

function Assert-AwsContext {
  $accountId = aws sts get-caller-identity --query Account --output text --region $Region
  if ($LASTEXITCODE -ne 0 -or $accountId -ne $ExpectedAccountId) {
    throw "Unexpected AWS account. Expected $ExpectedAccountId."
  }
}

function Assert-BackendServiceFamily {
  $taskDefinition = aws ecs describe-services --cluster $Cluster --services $Service --region $Region --query "services[0].taskDefinition" --output text
  $family = aws ecs describe-task-definition --task-definition $taskDefinition --region $Region --query "taskDefinition.family" --output text
  if ($LASTEXITCODE -ne 0 -or $family -ne $TaskFamily) {
    throw "Unexpected ECS task family. Expected $TaskFamily."
  }
}

function Wait-ForRdsStatus([string]$ExpectedStatus) {
  $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
  do {
    $status = aws rds describe-db-instances --db-instance-identifier $Database --region $Region --query "DBInstances[0].DBInstanceStatus" --output text
    if ($LASTEXITCODE -ne 0) { throw "RDS status check failed." }
    if ($status -eq $ExpectedStatus) { return }
    Start-Sleep -Seconds 15
  } while ((Get-Date) -lt $deadline)
  throw "RDS did not reach $ExpectedStatus within $TimeoutSeconds seconds."
}

Assert-AwsContext
$rdsStatus = aws rds describe-db-instances --db-instance-identifier $Database --region $Region --query "DBInstances[0].DBInstanceStatus" --output text
if ($rdsStatus -eq "stopped") {
  aws rds start-db-instance --db-instance-identifier $Database --region $Region | Out-Null
  if ($LASTEXITCODE -ne 0) { throw "RDS start request failed." }
}
elseif ($rdsStatus -notin @("available", "starting")) {
  throw "RDS cannot be started from state: $rdsStatus"
}
Wait-ForRdsStatus "available"

Assert-BackendServiceFamily
aws ecs update-service --cluster $Cluster --service $Service --desired-count 2 --region $Region | Out-Null
if ($LASTEXITCODE -ne 0) { throw "ECS desired count update failed." }
aws ecs wait services-stable --cluster $Cluster --services $Service --region $Region
if ($LASTEXITCODE -ne 0) { throw "ECS service did not stabilize." }

Write-Host "Production compute is up. Confirm ALB target health before accepting traffic." -ForegroundColor Green
