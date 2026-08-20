# 기존 import 주소를 lifecycle 스위치가 사용하는 count 주소로 이전한다.
moved {
  from = aws_eip.production_nat
  to   = aws_eip.production_nat[0]
}

moved {
  from = aws_nat_gateway.production
  to   = aws_nat_gateway.production[0]
}

moved {
  from = aws_lb.production
  to   = aws_lb.production[0]
}

moved {
  from = aws_lb_target_group.backend_blue
  to   = aws_lb_target_group.backend_blue[0]
}

moved {
  from = aws_lb_target_group.backend_green
  to   = aws_lb_target_group.backend_green[0]
}

moved {
  from = aws_lb_listener.http
  to   = aws_lb_listener.http[0]
}

moved {
  from = aws_lb_listener.https
  to   = aws_lb_listener.https[0]
}

moved {
  from = aws_lb_listener_rule.green_test
  to   = aws_lb_listener_rule.green_test[0]
}

moved {
  from = aws_route53_record.api
  to   = aws_route53_record.api[0]
}

moved {
  from = aws_ecs_service.backend
  to   = aws_ecs_service.backend[0]
}
