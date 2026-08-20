variable "production_storage_s3_bucket" {
  description = "운영 매장·메뉴 공개 이미지를 저장할 전용 S3 버킷 이름"
  type        = string
  default     = "miriyum-production-files-579750808837-ap-northeast-2"
}

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
