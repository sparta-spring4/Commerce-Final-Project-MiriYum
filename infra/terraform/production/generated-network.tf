# __generated__ by Terraform
# Please review these resources and move them into your main configuration files.

# __generated__ by Terraform from "igw-0a91bd00c2c430a16"
resource "aws_internet_gateway" "production" {
  lifecycle {
    prevent_destroy = true
  }

  region = "ap-northeast-2"
  tags = {
    Name = "miriyum-prod-igw"
  }
  tags_all = {
    Name = "miriyum-prod-igw"
  }
  vpc_id = "vpc-01826b348527334c1"
}

# __generated__ by Terraform
resource "aws_nat_gateway" "production" {
  count                    = var.production_infrastructure_enabled ? 1 : 0
  allocation_id            = aws_eip.production_nat[0].id
  availability_mode        = "zonal"
  connectivity_type        = "public"
  private_ip               = "10.0.6.20"
  region                   = "ap-northeast-2"
  secondary_allocation_ids = []
  subnet_id                = "subnet-02d8f250e109365b3"
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

# __generated__ by Terraform
resource "aws_subnet" "private_a" {
  lifecycle {
    prevent_destroy = true
  }

  assign_ipv6_address_on_creation                = false
  availability_zone                              = "ap-northeast-2a"
  cidr_block                                     = "10.0.128.0/20"
  enable_dns64                                   = false
  enable_resource_name_dns_a_record_on_launch    = false
  enable_resource_name_dns_aaaa_record_on_launch = false
  ipv4_ipam_pool_id                              = null
  ipv4_netmask_length                            = null
  ipv6_ipam_pool_id                              = null
  ipv6_native                                    = false
  ipv6_netmask_length                            = null
  map_public_ip_on_launch                        = false
  private_dns_hostname_type_on_launch            = "ip-name"
  region                                         = "ap-northeast-2"
  tags = {
    Name = "miriyum-prod-private-a"
  }
  tags_all = {
    Name = "miriyum-prod-private-a"
  }
  vpc_id = "vpc-01826b348527334c1"
}

# __generated__ by Terraform
resource "aws_subnet" "public_b" {
  lifecycle {
    prevent_destroy = true
  }

  assign_ipv6_address_on_creation                = false
  availability_zone                              = "ap-northeast-2b"
  cidr_block                                     = "10.0.16.0/20"
  enable_dns64                                   = false
  enable_resource_name_dns_a_record_on_launch    = false
  enable_resource_name_dns_aaaa_record_on_launch = false
  ipv4_ipam_pool_id                              = null
  ipv4_netmask_length                            = null
  ipv6_ipam_pool_id                              = null
  ipv6_native                                    = false
  ipv6_netmask_length                            = null
  map_public_ip_on_launch                        = false
  private_dns_hostname_type_on_launch            = "ip-name"
  region                                         = "ap-northeast-2"
  tags = {
    Name = "miriyum-prod-public-b"
  }
  tags_all = {
    Name = "miriyum-prod-public-b"
  }
  vpc_id = "vpc-01826b348527334c1"
}

# __generated__ by Terraform
resource "aws_subnet" "private_c" {
  lifecycle {
    prevent_destroy = true
  }

  assign_ipv6_address_on_creation                = false
  availability_zone                              = "ap-northeast-2a"
  cidr_block                                     = "10.0.160.0/20"
  enable_dns64                                   = false
  enable_resource_name_dns_a_record_on_launch    = false
  enable_resource_name_dns_aaaa_record_on_launch = false
  ipv4_ipam_pool_id                              = null
  ipv4_netmask_length                            = null
  ipv6_ipam_pool_id                              = null
  ipv6_native                                    = false
  ipv6_netmask_length                            = null
  map_public_ip_on_launch                        = false
  private_dns_hostname_type_on_launch            = "ip-name"
  region                                         = "ap-northeast-2"
  tags = {
    Name = "miriyum-prod-private-c"
  }
  tags_all = {
    Name = "miriyum-prod-private-c"
  }
  vpc_id = "vpc-01826b348527334c1"
}

# __generated__ by Terraform
resource "aws_subnet" "public_a" {
  lifecycle {
    prevent_destroy = true
  }

  assign_ipv6_address_on_creation                = false
  availability_zone                              = "ap-northeast-2a"
  cidr_block                                     = "10.0.0.0/20"
  enable_dns64                                   = false
  enable_resource_name_dns_a_record_on_launch    = false
  enable_resource_name_dns_aaaa_record_on_launch = false
  ipv4_ipam_pool_id                              = null
  ipv4_netmask_length                            = null
  ipv6_ipam_pool_id                              = null
  ipv6_native                                    = false
  ipv6_netmask_length                            = null
  map_public_ip_on_launch                        = false
  private_dns_hostname_type_on_launch            = "ip-name"
  region                                         = "ap-northeast-2"
  tags = {
    Name = "miriyum-prod-public-a"
  }
  tags_all = {
    Name = "miriyum-prod-public-a"
  }
  vpc_id = "vpc-01826b348527334c1"
}

# __generated__ by Terraform
resource "aws_subnet" "private_d" {
  lifecycle {
    prevent_destroy = true
  }

  assign_ipv6_address_on_creation                = false
  availability_zone                              = "ap-northeast-2b"
  cidr_block                                     = "10.0.176.0/20"
  enable_dns64                                   = false
  enable_resource_name_dns_a_record_on_launch    = false
  enable_resource_name_dns_aaaa_record_on_launch = false
  ipv4_ipam_pool_id                              = null
  ipv4_netmask_length                            = null
  ipv6_ipam_pool_id                              = null
  ipv6_native                                    = false
  ipv6_netmask_length                            = null
  map_public_ip_on_launch                        = false
  private_dns_hostname_type_on_launch            = "ip-name"
  region                                         = "ap-northeast-2"
  tags = {
    Name = "miriyum-prod-private-d"
  }
  tags_all = {
    Name = "miriyum-prod-private-d"
  }
  vpc_id = "vpc-01826b348527334c1"
}

# __generated__ by Terraform
resource "aws_subnet" "private_b" {
  lifecycle {
    prevent_destroy = true
  }

  assign_ipv6_address_on_creation                = false
  availability_zone                              = "ap-northeast-2b"
  cidr_block                                     = "10.0.144.0/20"
  enable_dns64                                   = false
  enable_resource_name_dns_a_record_on_launch    = false
  enable_resource_name_dns_aaaa_record_on_launch = false
  ipv4_ipam_pool_id                              = null
  ipv4_netmask_length                            = null
  ipv6_ipam_pool_id                              = null
  ipv6_native                                    = false
  ipv6_netmask_length                            = null
  map_public_ip_on_launch                        = false
  private_dns_hostname_type_on_launch            = "ip-name"
  region                                         = "ap-northeast-2"
  tags = {
    Name = "miriyum-prod-private-b"
  }
  tags_all = {
    Name = "miriyum-prod-private-b"
  }
  vpc_id = "vpc-01826b348527334c1"
}

# __generated__ by Terraform from "eipalloc-044e425ddb3635bf5"
resource "aws_eip" "production_nat" {
  count = var.production_infrastructure_enabled ? 1 : 0

  address                   = null
  associate_with_private_ip = null
  customer_owned_ipv4_pool  = null
  domain                    = "vpc"
  network_border_group      = "ap-northeast-2"
  public_ipv4_pool          = "amazon"
  region                    = "ap-northeast-2"
  tags                      = {}
  tags_all                  = {}
}

# __generated__ by Terraform
resource "aws_vpc" "production" {
  lifecycle {
    prevent_destroy = true
  }

  assign_generated_ipv6_cidr_block     = false
  cidr_block                           = "10.0.0.0/16"
  enable_dns_hostnames                 = true
  enable_dns_support                   = true
  enable_network_address_usage_metrics = false
  instance_tenancy                     = "default"
  ipv4_ipam_pool_id                    = null
  ipv4_netmask_length                  = null
  ipv6_ipam_pool_id                    = null
  region                               = "ap-northeast-2"
  tags = {
    Name = "miriyum-prod-vpc"
  }
  tags_all = {
    Name = "miriyum-prod-vpc"
  }
}
