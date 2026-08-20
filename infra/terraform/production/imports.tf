# `terraform plan` 단계에서는 AWS 리소스를 변경하지 않는다.
# 기존 RDS 설정을 Terraform 코드로 생성하기 위한 import 선언이다.
import {
  to = aws_db_instance.production
  id = "miriyum-prod-mysql"
}

import {
  to = aws_vpc.production
  id = "vpc-01826b348527334c1"
}

import {
  to = aws_internet_gateway.production
  id = "igw-0a91bd00c2c430a16"
}

import {
  to = aws_subnet.public_a
  id = "subnet-02d8f250e109365b3"
}

import {
  to = aws_subnet.public_b
  id = "subnet-01ad0d1d79c220458"
}

import {
  to = aws_subnet.private_a
  id = "subnet-0c8358a8e649b8af2"
}

import {
  to = aws_subnet.private_b
  id = "subnet-0b25124d3399a0f95"
}

import {
  to = aws_subnet.private_c
  id = "subnet-08014d2fae8ef8ba8"
}

import {
  to = aws_subnet.private_d
  id = "subnet-077e4f28efb9ee422"
}

import {
  to = aws_route_table.private_4b
  id = "rtb-0951a85913893442e"
}

import {
  to = aws_route_table.main
  id = "rtb-0111fed2f47b47928"
}

import {
  to = aws_route_table.private_2b
  id = "rtb-00ada8705bf45445d"
}

import {
  to = aws_route_table.private
  id = "rtb-0221367d693edbb5e"
}

import {
  to = aws_route_table.private_1a
  id = "rtb-051fb12f6a70a5fef"
}

import {
  to = aws_route_table.private_3a
  id = "rtb-0368ce8eddfcac0cf"
}

import {
  to = aws_route_table.public
  id = "rtb-0c03fd23b557b007b"
}

import {
  to = aws_security_group.alb
  id = "sg-0ad71a045d2568be1"
}

import {
  to = aws_security_group.rds
  id = "sg-02fe40378345a2228"
}

import {
  to = aws_security_group.valkey
  id = "sg-0c4dc6d06d87b032f"
}

import {
  to = aws_security_group.ecs
  id = "sg-0f749e33c9e7bc657"
}
