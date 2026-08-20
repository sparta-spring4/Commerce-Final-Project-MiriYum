# __generated__ by Terraform
# Please review these resources and move them into your main configuration files.

# __generated__ by Terraform from "arn:aws:elasticloadbalancing:ap-northeast-2:579750808837:listener-rule/app/miriyum-prod-alb/7c09b22da0213b9c/56cd56a25b0f93a6/9c017bbaeeb20bfc"
resource "aws_lb_listener_rule" "green_test" {
  count        = var.production_infrastructure_enabled ? 1 : 0
  listener_arn = aws_lb_listener.https[0].arn
  priority     = 10
  region       = "ap-northeast-2"
  tags         = {}
  tags_all     = {}
  action {
    order            = 1
    target_group_arn = null
    type             = "forward"
    forward {
      stickiness {
        duration = 3600
        enabled  = false
      }
      target_group {
        arn    = aws_lb_target_group.backend_green[0].arn
        weight = 100
      }
      target_group {
        arn    = aws_lb_target_group.backend_blue[0].arn
        weight = 0
      }
    }
  }
  condition {
    path_pattern {
      regex_values = []
      values       = ["/__green-test"]
    }
  }
  lifecycle {
    ignore_changes = [action]
  }
}

resource "aws_lb_listener_rule" "production" {
  count        = var.production_infrastructure_enabled ? 1 : 0
  listener_arn = aws_lb_listener.https[0].arn
  priority     = 20

  action {
    type = "forward"
    forward {
      stickiness {
        duration = 3600
        enabled  = false
      }
      target_group {
        arn    = aws_lb_target_group.backend_green[0].arn
        weight = 100
      }
      target_group {
        arn    = aws_lb_target_group.backend_blue[0].arn
        weight = 0
      }
    }
  }

  condition {
    path_pattern {
      values = ["/*"]
    }
  }

  lifecycle {
    ignore_changes = [action]
  }
}

# __generated__ by Terraform
resource "aws_route53_record" "api" {
  count   = var.production_infrastructure_enabled ? 1 : 0
  name    = "api.miriyum.click"
  type    = "A"
  zone_id = "Z0422064HUCY204BJ1Y0"
  alias {
    evaluate_target_health = true
    name                   = "dualstack.${aws_lb.production[0].dns_name}"
    zone_id                = aws_lb.production[0].zone_id
  }
}
