# __generated__ by Terraform
# Please review these resources and move them into your main configuration files.

# __generated__ by Terraform from "arn:aws:elasticloadbalancing:ap-northeast-2:579750808837:listener/app/miriyum-prod-alb/7c09b22da0213b9c/059bb23fc8c8a13c"
resource "aws_lb_listener" "http" {
  count                                = var.production_infrastructure_enabled ? 1 : 0
  alpn_policy                          = null
  certificate_arn                      = null
  load_balancer_arn                    = "arn:aws:elasticloadbalancing:ap-northeast-2:579750808837:loadbalancer/app/miriyum-prod-alb/7c09b22da0213b9c"
  port                                 = 80
  protocol                             = "HTTP"
  region                               = "ap-northeast-2"
  routing_http_response_server_enabled = true
  tags = {
    environment = "production"
    expires-at  = "2026-08-31"
    owner       = "easyhyeon3232"
    project     = "miriyum"
  }
  tags_all = {
    environment = "production"
    expires-at  = "2026-08-31"
    owner       = "easyhyeon3232"
    project     = "miriyum"
  }
  default_action {
    order            = 1
    target_group_arn = null
    type             = "redirect"
    redirect {
      host        = "#{host}"
      path        = "/#{path}"
      port        = "443"
      protocol    = "HTTPS"
      query       = "#{query}"
      status_code = "HTTP_301"
    }
  }
}

# __generated__ by Terraform
resource "aws_lb_target_group" "backend_blue" {
  count                              = var.production_infrastructure_enabled ? 1 : 0
  deregistration_delay               = "300"
  ip_address_type                    = "ipv4"
  lambda_multi_value_headers_enabled = null
  load_balancing_algorithm_type      = "round_robin"
  load_balancing_anomaly_mitigation  = "off"
  load_balancing_cross_zone_enabled  = "use_load_balancer_configuration"
  name                               = "miriyum-prod-backend-tg"
  port                               = 8080
  protocol                           = "HTTP"
  protocol_version                   = "HTTP1"
  proxy_protocol_v2                  = null
  region                             = "ap-northeast-2"
  slow_start                         = 0
  tags = {
    environment = "production"
    expires-at  = "2026-08-31"
    owner       = "easyhyeon3232"
    project     = "miriyum"
  }
  tags_all = {
    environment = "production"
    expires-at  = "2026-08-31"
    owner       = "easyhyeon3232"
    project     = "miriyum"
  }
  target_type = "ip"
  vpc_id      = "vpc-01826b348527334c1"
  health_check {
    enabled             = true
    healthy_threshold   = 5
    interval            = 30
    matcher             = "200"
    path                = "/actuator/health"
    port                = "traffic-port"
    protocol            = "HTTP"
    timeout             = 5
    unhealthy_threshold = 2
  }
  stickiness {
    cookie_duration = 86400
    cookie_name     = null
    enabled         = false
    type            = "lb_cookie"
  }
  target_group_health {
    dns_failover {
      minimum_healthy_targets_count      = "1"
      minimum_healthy_targets_percentage = "off"
    }
    unhealthy_state_routing {
      minimum_healthy_targets_count      = 1
      minimum_healthy_targets_percentage = "off"
    }
  }
}

# __generated__ by Terraform
resource "aws_lb_target_group" "backend_green" {
  count                              = var.production_infrastructure_enabled ? 1 : 0
  deregistration_delay               = "300"
  ip_address_type                    = "ipv4"
  lambda_multi_value_headers_enabled = null
  load_balancing_algorithm_type      = "round_robin"
  load_balancing_anomaly_mitigation  = "off"
  load_balancing_cross_zone_enabled  = "use_load_balancer_configuration"
  name                               = "miriyum-prod-backend-green-tg"
  port                               = 8080
  protocol                           = "HTTP"
  protocol_version                   = "HTTP1"
  proxy_protocol_v2                  = null
  region                             = "ap-northeast-2"
  slow_start                         = 0
  tags                               = {}
  tags_all                           = {}
  target_type                        = "ip"
  vpc_id                             = "vpc-01826b348527334c1"
  health_check {
    enabled             = true
    healthy_threshold   = 5
    interval            = 30
    matcher             = "200"
    path                = "/actuator/health"
    port                = "traffic-port"
    protocol            = "HTTP"
    timeout             = 5
    unhealthy_threshold = 2
  }
  stickiness {
    cookie_duration = 86400
    cookie_name     = null
    enabled         = false
    type            = "lb_cookie"
  }
  target_group_health {
    dns_failover {
      minimum_healthy_targets_count      = "1"
      minimum_healthy_targets_percentage = "off"
    }
    unhealthy_state_routing {
      minimum_healthy_targets_count      = 1
      minimum_healthy_targets_percentage = "off"
    }
  }
}

# __generated__ by Terraform
resource "aws_lb_listener" "https" {
  count                                = var.production_infrastructure_enabled ? 1 : 0
  alpn_policy                          = null
  certificate_arn                      = "arn:aws:acm:ap-northeast-2:579750808837:certificate/4cfcbc97-1570-4319-9950-2243ab32be86"
  load_balancer_arn                    = "arn:aws:elasticloadbalancing:ap-northeast-2:579750808837:loadbalancer/app/miriyum-prod-alb/7c09b22da0213b9c"
  port                                 = 443
  protocol                             = "HTTPS"
  region                               = "ap-northeast-2"
  routing_http_response_server_enabled = true
  ssl_policy                           = "ELBSecurityPolicy-TLS13-1-2-Res-PQ-2025-09"
  tags                                 = {}
  tags_all                             = {}
  default_action {
    order            = 1
    target_group_arn = null
    type             = "forward"
    forward {
      stickiness {
        duration = 3600
        enabled  = false
      }
      target_group {
        arn    = "arn:aws:elasticloadbalancing:ap-northeast-2:579750808837:targetgroup/miriyum-prod-backend-green-tg/77960523d4c07477"
        weight = 100
      }
      target_group {
        arn    = "arn:aws:elasticloadbalancing:ap-northeast-2:579750808837:targetgroup/miriyum-prod-backend-tg/ada02b5468fc8e3f"
        weight = 0
      }
    }
  }
  lifecycle {
    # ECS Blue/Green 배포가 listener 가중치를 일시적으로 전환한다.
    ignore_changes = [default_action]
  }
}

# __generated__ by Terraform
resource "aws_lb" "production" {
  count                                       = var.production_infrastructure_enabled ? 1 : 0
  client_keep_alive                           = 3600
  customer_owned_ipv4_pool                    = null
  desync_mitigation_mode                      = "defensive"
  dns_record_client_routing_policy            = null
  drop_invalid_header_fields                  = false
  enable_cross_zone_load_balancing            = true
  enable_deletion_protection                  = false
  enable_http2                                = true
  enable_prefix_for_ipv6_source_nat           = "off"
  enable_tls_version_and_cipher_suite_headers = false
  enable_waf_fail_open                        = false
  enable_xff_client_port                      = false
  enable_zonal_shift                          = false
  idle_timeout                                = 60
  internal                                    = false
  ip_address_type                             = "ipv4"
  load_balancer_type                          = "application"
  name                                        = "miriyum-prod-alb"
  preserve_host_header                        = false
  region                                      = "ap-northeast-2"
  security_groups                             = ["sg-0ad71a045d2568be1"]
  subnets                                     = ["subnet-01ad0d1d79c220458", "subnet-02d8f250e109365b3"]
  tags                                        = {}
  tags_all                                    = {}
  xff_header_processing_mode                  = "append"
  access_logs {
    bucket  = ""
    enabled = false
    prefix  = null
  }
  connection_logs {
    bucket  = ""
    enabled = false
    prefix  = null
  }
  health_check_logs {
    bucket  = ""
    enabled = false
    prefix  = null
  }
  lifecycle {
  }
}

# __generated__ by Terraform from "miriyum-prod-cluster/miriyum-prod-backend-service"
resource "aws_ecs_service" "backend" {
  count                              = var.production_infrastructure_enabled ? 1 : 0
  availability_zone_rebalancing      = "ENABLED"
  cluster                            = "arn:aws:ecs:ap-northeast-2:579750808837:cluster/miriyum-prod-cluster"
  deployment_maximum_percent         = 200
  deployment_minimum_healthy_percent = 100
  desired_count                      = var.production_backend_desired_count
  enable_ecs_managed_tags            = true
  enable_execute_command             = false
  force_delete                       = null
  force_new_deployment               = null
  health_check_grace_period_seconds  = 300
  iam_role                           = "/aws-service-role/ecs.amazonaws.com/AWSServiceRoleForECS"
  name                               = "miriyum-prod-backend-service"
  platform_version                   = "1.4.0"
  propagate_tags                     = "NONE"
  region                             = "ap-northeast-2"
  scheduling_strategy                = "REPLICA"
  sigint_rollback                    = null
  tags                               = {}
  tags_all                           = {}
  task_definition                    = var.production_backend_task_definition
  triggers                           = {}
  wait_for_steady_state              = null
  capacity_provider_strategy {
    base              = 0
    capacity_provider = "FARGATE"
    weight            = 1
  }
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }
  deployment_configuration {
    bake_time_in_minutes = "5"
    strategy             = "BLUE_GREEN"
  }
  deployment_controller {
    type = "ECS"
  }
  load_balancer {
    container_name   = "backend"
    container_port   = 8080
    elb_name         = null
    target_group_arn = aws_lb_target_group.backend_blue[0].arn
    advanced_configuration {
      alternate_target_group_arn = aws_lb_target_group.backend_green[0].arn
      production_listener_rule   = aws_lb_listener_rule.production[0].arn
      role_arn                   = "arn:aws:iam::579750808837:role/miriyum-prod-ecs-infrastructure-role"
      test_listener_rule         = aws_lb_listener_rule.green_test[0].arn
    }
  }
  network_configuration {
    assign_public_ip = false
    security_groups  = ["sg-0f749e33c9e7bc657"]
    subnets          = ["subnet-077e4f28efb9ee422", "subnet-08014d2fae8ef8ba8", "subnet-0b25124d3399a0f95", "subnet-0c8358a8e649b8af2"]
  }
  lifecycle {
    # 기존 ECS Service의 Blue/Green listener rule은 in-place 변경되지 않는다.
    # OFF 후 ON으로 새 Service를 생성할 때는 위 load_balancer 구성이 적용된다.
    # CD가 등록한 최신 task definition revision도 Terraform이 되돌리지 않는다.
    ignore_changes = [task_definition, load_balancer]
  }
}
