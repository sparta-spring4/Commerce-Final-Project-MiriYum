terraform {
  backend "s3" {
    bucket = "miriyum-terraform-state-579750808837-ap-northeast-2"
    # Legacy production/terraform.tfstate owns the originally imported VPC,
    # ALB, ECS, and RDS resources. Keep runtime resources in a separate state
    # so this narrow configuration can never plan their destruction.
    key          = "production/runtime-resources.tfstate"
    region       = "ap-northeast-2"
    encrypt      = true
    use_lockfile = true
  }
}
