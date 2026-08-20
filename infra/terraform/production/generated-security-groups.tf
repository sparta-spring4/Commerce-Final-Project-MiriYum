# __generated__ by Terraform
# Please review these resources and move them into your main configuration files.

# __generated__ by Terraform from "sg-0f749e33c9e7bc657"
resource "aws_security_group" "ecs" {
  description = "prod ECS backend allow from ALB"
  egress = [{
    cidr_blocks      = ["0.0.0.0/0"]
    description      = ""
    from_port        = 0
    ipv6_cidr_blocks = []
    prefix_list_ids  = []
    protocol         = "-1"
    security_groups  = []
    self             = false
    to_port          = 0
  }]
  ingress = [{
    cidr_blocks      = []
    description      = ""
    from_port        = 8080
    ipv6_cidr_blocks = []
    prefix_list_ids  = []
    protocol         = "tcp"
    security_groups  = ["sg-0ad71a045d2568be1"]
    self             = false
    to_port          = 8080
  }]
  name                   = "miriyum-prod-ecs-sg"
  region                 = "ap-northeast-2"
  revoke_rules_on_delete = null
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
  vpc_id = "vpc-01826b348527334c1"
}

# __generated__ by Terraform from "sg-0c4dc6d06d87b032f"
resource "aws_security_group" "valkey" {
  description = "prod Valkey allow from ECS"
  egress = [{
    cidr_blocks      = ["0.0.0.0/0"]
    description      = ""
    from_port        = 0
    ipv6_cidr_blocks = []
    prefix_list_ids  = []
    protocol         = "-1"
    security_groups  = []
    self             = false
    to_port          = 0
  }]
  ingress = [{
    cidr_blocks      = []
    description      = ""
    from_port        = 6379
    ipv6_cidr_blocks = []
    prefix_list_ids  = []
    protocol         = "tcp"
    security_groups  = ["sg-0f749e33c9e7bc657"]
    self             = false
    to_port          = 6379
  }]
  name                   = "miriyum-prod-valkey-sg"
  region                 = "ap-northeast-2"
  revoke_rules_on_delete = null
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
  vpc_id = "vpc-01826b348527334c1"
}

# __generated__ by Terraform from "sg-0ad71a045d2568be1"
resource "aws_security_group" "alb" {
  description = "prod ALB HTTP HTTPS allow"
  egress = [{
    cidr_blocks      = ["0.0.0.0/0"]
    description      = ""
    from_port        = 0
    ipv6_cidr_blocks = []
    prefix_list_ids  = []
    protocol         = "-1"
    security_groups  = []
    self             = false
    to_port          = 0
  }]
  ingress = [{
    cidr_blocks      = ["0.0.0.0/0"]
    description      = ""
    from_port        = 443
    ipv6_cidr_blocks = []
    prefix_list_ids  = []
    protocol         = "tcp"
    security_groups  = []
    self             = false
    to_port          = 443
    }, {
    cidr_blocks      = ["0.0.0.0/0"]
    description      = ""
    from_port        = 80
    ipv6_cidr_blocks = []
    prefix_list_ids  = []
    protocol         = "tcp"
    security_groups  = []
    self             = false
    to_port          = 80
  }]
  name                   = "miriyum-prod-alb-sg"
  region                 = "ap-northeast-2"
  revoke_rules_on_delete = null
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
  vpc_id = "vpc-01826b348527334c1"
}

# __generated__ by Terraform from "sg-02fe40378345a2228"
resource "aws_security_group" "rds" {
  description = "prod RDS MySQL allow from ECS"
  egress = [{
    cidr_blocks      = ["0.0.0.0/0"]
    description      = ""
    from_port        = 0
    ipv6_cidr_blocks = []
    prefix_list_ids  = []
    protocol         = "-1"
    security_groups  = []
    self             = false
    to_port          = 0
  }]
  ingress = [{
    cidr_blocks      = []
    description      = ""
    from_port        = 3306
    ipv6_cidr_blocks = []
    prefix_list_ids  = []
    protocol         = "tcp"
    security_groups  = ["sg-0f749e33c9e7bc657"]
    self             = false
    to_port          = 3306
  }]
  name                   = "miriyum-prod-rds-sg"
  region                 = "ap-northeast-2"
  revoke_rules_on_delete = null
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
  vpc_id = "vpc-01826b348527334c1"
}
