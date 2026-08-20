variable "aws_region" {
  type    = string
  default = "ap-northeast-2"
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
