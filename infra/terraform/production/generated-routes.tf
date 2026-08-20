# __generated__ by Terraform
# Please review these resources and move them into your main configuration files.

# __generated__ by Terraform from "rtb-0368ce8eddfcac0cf"
resource "aws_route_table" "private_3a" {
  lifecycle {
    prevent_destroy = true
  }

  propagating_vgws = []
  region           = "ap-northeast-2"
  route            = []
  tags = {
    Name = "miriyum-prod-rtb-private3-ap-northeast-2a"
  }
  tags_all = {
    Name = "miriyum-prod-rtb-private3-ap-northeast-2a"
  }
  vpc_id = "vpc-01826b348527334c1"
}

# __generated__ by Terraform from "rtb-0111fed2f47b47928"
resource "aws_route_table" "main" {
  lifecycle {
    prevent_destroy = true
  }

  propagating_vgws = []
  region           = "ap-northeast-2"
  route            = []
  tags             = {}
  tags_all         = {}
  vpc_id           = "vpc-01826b348527334c1"
}

# __generated__ by Terraform
resource "aws_route_table" "public" {
  lifecycle {
    prevent_destroy = true
  }

  propagating_vgws = []
  region           = "ap-northeast-2"
  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = "igw-0a91bd00c2c430a16"
  }
  tags = {
    Name = "miriyum-prod-rtb-public"
  }
  tags_all = {
    Name = "miriyum-prod-rtb-public"
  }
  vpc_id = "vpc-01826b348527334c1"
}

# __generated__ by Terraform from "rtb-00ada8705bf45445d"
resource "aws_route_table" "private_2b" {
  lifecycle {
    prevent_destroy = true
  }

  propagating_vgws = []
  region           = "ap-northeast-2"
  route            = []
  tags = {
    Name = "miriyum-prod-rtb-private2-ap-northeast-2b"
  }
  tags_all = {
    Name = "miriyum-prod-rtb-private2-ap-northeast-2b"
  }
  vpc_id = "vpc-01826b348527334c1"
}

# __generated__ by Terraform from "rtb-0951a85913893442e"
resource "aws_route_table" "private_4b" {
  lifecycle {
    prevent_destroy = true
  }

  propagating_vgws = []
  region           = "ap-northeast-2"
  route            = []
  tags = {
    Name = "miriyum-prod-rtb-private4-ap-northeast-2b"
  }
  tags_all = {
    Name = "miriyum-prod-rtb-private4-ap-northeast-2b"
  }
  vpc_id = "vpc-01826b348527334c1"
}

# __generated__ by Terraform from "rtb-051fb12f6a70a5fef"
resource "aws_route_table" "private_1a" {
  lifecycle {
    prevent_destroy = true
  }

  propagating_vgws = []
  region           = "ap-northeast-2"
  route            = []
  tags = {
    Name = "miriyum-prod-rtb-private1-ap-northeast-2a"
  }
  tags_all = {
    Name = "miriyum-prod-rtb-private1-ap-northeast-2a"
  }
  vpc_id = "vpc-01826b348527334c1"
}

# __generated__ by Terraform
resource "aws_route_table" "private" {
  lifecycle {
    prevent_destroy = true
  }

  propagating_vgws = []
  region           = "ap-northeast-2"
  dynamic "route" {
    for_each = var.production_infrastructure_enabled ? [aws_nat_gateway.production[0].id] : []
    content {
      cidr_block     = "0.0.0.0/0"
      nat_gateway_id = route.value
    }
  }
  tags = {
    Name = "miriyum-prod-rtb-private"
  }
  tags_all = {
    Name = "miriyum-prod-rtb-private"
  }
  vpc_id = "vpc-01826b348527334c1"
}
