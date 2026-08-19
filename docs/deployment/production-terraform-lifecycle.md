# Production Terraform lifecycle runbook

## Purpose and boundary

This runbook describes the approved cost-controlled lifecycle for the production
runtime. Terraform owns the disposable delivery path: ECS service, ALB,
listeners, target groups, NAT gateway, its EIP, and the `api` Route 53 alias.
RDS is retained as the production data store and is stopped separately when the
delivery path is down.

This is not a database deletion procedure. It does not authorize deleting RDS
data, snapshots, secrets, or the production hosted zone. Do not place secret
values, account identifiers, image credentials, or customer data in a terminal
capture or an Issue.

The Terraform module and its lifecycle scripts must be present in the selected
worktree before this procedure is used. A plan is the only source of truth for
the exact resources that will change; names in this runbook are examples, not
authorization to target a different environment.

## Preconditions

1. Confirm the selected AWS account and region are production.
2. Use the production Terraform state backend; do not initialize a local state
   for an existing environment.
3. Confirm the worktree is on the reviewed infrastructure revision and has no
   unrelated changes.
4. Record the current backend task definition/image SHA and the RDS status.
5. Before a shutdown, confirm that no release, load test, migration, or incident
   response requires the public production path.

```powershell
terraform -chdir=infra/terraform/production init
terraform -chdir=infra/terraform/production plan -no-color
```

If the plan proposes a database destroy, a hosted-zone destroy, an unexpected
subnet/security-group destroy, or any resource outside the approved lifecycle,
stop. Reconcile the Terraform state and configuration before applying.

## Stop delivery resources to reduce cost

The reviewed `production-down.ps1` procedure must first show its destructive
scope and require the literal confirmation `DOWN`. It destroys only the
disposable delivery resources and then requests an RDS stop.

Expected retained state:

- RDS instance and its data, automated backup policy, and snapshots
- VPC, subnets, route tables, security groups, Internet Gateway, and DNS zone
- Secrets/SSM parameters and ECR images

Expected removed state:

- ECS backend service and running Fargate tasks
- ALB, listeners, listener rules, and target groups
- NAT gateway and its EIP allocation
- `api.miriyum.click` alias record

RDS stop is asynchronous. The lifecycle script must wait for `stopped`; it must
not treat command submission as proof that the database is stopped. AWS can
restart a stopped RDS instance after its service limit, so long idle periods
still require a periodic cost review.

## Start delivery resources

The reviewed `production-up.ps1` procedure starts RDS, waits for `available`,
then applies Terraform with the approved backend task definition and a desired
count above zero. It recreates the NAT path before a private Fargate task needs
outbound traffic, including provider HTTPS calls.

Before applying, obtain the active backend task definition from ECS rather than
inventing an image tag:

```powershell
$taskDefinition = aws ecs list-task-definitions `
  --family-prefix miriyum-production-backend `
  --status ACTIVE `
  --sort DESC `
  --max-results 1 `
  --query "taskDefinitionArns[0]" `
  --output text

terraform -chdir=infra/terraform/production plan -no-color `
  -var production_infrastructure_enabled=true `
  -var production_backend_desired_count=1 `
  -var "production_backend_task_definition=$taskDefinition"
```

Review the plan before approval. The expected recovery plan creates the delivery
resources and may update the private default route to the newly created NAT
gateway. It must not destroy retained data/network resources.

## Recovery verification

Wait for ECS to stabilize; do not use a fixed sleep as proof of readiness.

```powershell
aws ecs describe-services `
  --cluster miriyum-prod-cluster `
  --services miriyum-prod-backend-service `
  --query "services[0].{status:status,desired:desiredCount,running:runningCount,pending:pendingCount}" `
  --output table

curl.exe -fsS https://api.miriyum.click/actuator/health
```

Recovery is complete only when the service is `ACTIVE`, desired and running
counts match, pending is zero, target health is healthy, and the public health
response reports `status: UP`. Record the applied Terraform revision, task
definition/image SHA, and non-secret verification results in the deployment
evidence; do not copy environment values into that record.

## Blue/Green and rollback boundary

The ECS service uses separate blue and green target groups. Terraform creates
the stable load-balancer topology; the ECS deployment controller owns temporary
listener weights during a deployment. Terraform must ignore only those temporary
weight changes and must not replace listener rules while a deployment is in
progress.

For an application rollback, first verify Flyway and runtime-secret compatibility
with the previous task definition, then deploy the last known healthy task
definition. Do not use a Terraform destroy or RDS restore as an application
rollback. See [Production ECS incident runbook](production-ecs-incident-runbook.md)
for incident classification.
