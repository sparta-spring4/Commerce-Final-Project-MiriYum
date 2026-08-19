variable "aws_region" {
  type    = string
  default = "ap-northeast-2"
}

variable "production_backend_desired_count" {
  description = "운영 ECS backend 태스크 수. 비용 절감 중에는 0, 운영 검증 시에는 1로 설정한다."
  type        = number
  default     = 0
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
