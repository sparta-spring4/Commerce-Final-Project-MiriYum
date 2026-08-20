locals {
  production_backend_autoscaling_resource_id = "service/miriyum-prod-cluster/miriyum-prod-backend-service"
}

# 기존 ECS service의 desired count만 최소 2개에서 최대 3개 사이로 조절한다.
# VPC, ALB, DNS 등 영속 인프라는 이 Terraform state에서 관리하지 않는다.
resource "aws_appautoscaling_target" "production_backend" {
  max_capacity       = var.production_backend_autoscaling_max_capacity
  min_capacity       = var.production_backend_autoscaling_min_capacity
  resource_id        = local.production_backend_autoscaling_resource_id
  scalable_dimension = "ecs:service:DesiredCount"
  service_namespace  = "ecs"

  # OFF/ON 스크립트는 RDS 상태와 함께 최소 용량·scaling 중지를 직접 전환한다.
  # Terraform apply가 OFF 상태를 다시 기동 상태로 되돌리지 않도록 런타임 값은 보존한다.
  lifecycle {
    ignore_changes = [
      min_capacity,
      suspended_state,
    ]
  }
}

# 평균 CPU가 목표를 넘으면 확장하고, 축소가 완료된 뒤 5분 동안 다음 축소를 막는다.
resource "aws_appautoscaling_policy" "production_backend_cpu" {
  name               = "miriyum-prod-backend-cpu-target"
  policy_type        = "TargetTrackingScaling"
  resource_id        = aws_appautoscaling_target.production_backend.resource_id
  scalable_dimension = aws_appautoscaling_target.production_backend.scalable_dimension
  service_namespace  = aws_appautoscaling_target.production_backend.service_namespace

  target_tracking_scaling_policy_configuration {
    target_value       = var.production_backend_autoscaling_cpu_target_percent
    scale_in_cooldown  = 300
    scale_out_cooldown = 60

    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }
  }
}
