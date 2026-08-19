variable "aws_region" {
  type    = string
  default = "ap-northeast-2"
}

variable "production_backend_desired_count" {
  description = "운영 ECS backend 태스크 수. 정상 운영은 1이며, 비용 절감 중지는 production-down.ps1이 명시적으로 0을 전달한다."
  type        = number
  default     = 1
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
