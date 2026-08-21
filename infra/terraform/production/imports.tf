# The target and policy were configured in the AWS console before this
# Terraform state existed. Adopt them rather than attempting duplicate creation.
import {
  to = aws_appautoscaling_target.production_backend
  id = "ecs/service/miriyum-prod-cluster/miriyum-prod-backend-service/ecs:service:DesiredCount"
}

import {
  to = aws_appautoscaling_policy.production_backend_cpu
  id = "ecs/service/miriyum-prod-cluster/miriyum-prod-backend-service/ecs:service:DesiredCount/miriyum-prod-backend-cpu-target"
}
