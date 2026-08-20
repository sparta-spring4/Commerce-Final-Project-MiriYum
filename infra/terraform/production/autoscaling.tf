locals {
  production_backend_autoscaling_resource_id = "service/miriyum-prod-cluster/miriyum-prod-backend-service"
}

# ECS가 실행 중인 태스크 수를 최소 2개에서 최대 3개 사이로 조절할 수 있게 등록한다.
resource "aws_appautoscaling_target" "production_backend" {
  count              = var.production_infrastructure_enabled ? 1 : 0
  max_capacity       = var.production_backend_autoscaling_max_capacity
  min_capacity       = var.production_backend_autoscaling_min_capacity
  resource_id        = local.production_backend_autoscaling_resource_id
  scalable_dimension = "ecs:service:DesiredCount"
  service_namespace  = "ecs"

  depends_on = [aws_ecs_service.backend]
}

# 평균 CPU가 60%를 넘으면 확장하고, 여유가 생기면 5분 동안 관찰한 뒤 축소한다.
resource "aws_appautoscaling_policy" "production_backend_cpu" {
  count              = var.production_infrastructure_enabled ? 1 : 0
  name               = "miriyum-prod-backend-cpu-target"
  policy_type        = "TargetTrackingScaling"
  resource_id        = aws_appautoscaling_target.production_backend[0].resource_id
  scalable_dimension = aws_appautoscaling_target.production_backend[0].scalable_dimension
  service_namespace  = aws_appautoscaling_target.production_backend[0].service_namespace

  target_tracking_scaling_policy_configuration {
    target_value       = var.production_backend_autoscaling_cpu_target_percent
    scale_in_cooldown  = 300
    scale_out_cooldown = 60

    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }
  }
}
