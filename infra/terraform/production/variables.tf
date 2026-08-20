variable "aws_region" {
  type    = string
  default = "ap-northeast-2"
}

variable "production_backend_desired_count" {
  description = "운영 ECS backend의 초기 태스크 수. Auto Scaling 최소 용량과 같거나 커야 한다."
  type        = number
  default     = 2
}

variable "production_backend_autoscaling_min_capacity" {
  description = "운영 ECS backend Auto Scaling 최소 태스크 수"
  type        = number
  default     = 2
}

variable "production_backend_autoscaling_max_capacity" {
  description = "운영 ECS backend Auto Scaling 최대 태스크 수"
  type        = number
  default     = 3
}

variable "production_backend_autoscaling_cpu_target_percent" {
  description = "ECS 서비스 평균 CPU 사용률 목표(%)"
  type        = number
  default     = 60
}

variable "production_infrastructure_enabled" {
  description = "false면 비용 절감을 위해 ECS, ALB, NAT Gateway, API DNS를 제거한다. RDS는 삭제하지 않는다."
  type        = bool
  default     = true
}

variable "production_backend_task_definition" {
  description = "운영 ECS service를 새로 생성할 때 사용할 task definition ARN 또는 family:revision"
  type        = string
  default     = "miriyum-production-backend:17"
}
