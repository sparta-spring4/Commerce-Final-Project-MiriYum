terraform {
  backend "s3" {
    bucket       = "miriyum-terraform-state-579750808837-ap-northeast-2"
    key          = "production/terraform.tfstate"
    region       = "ap-northeast-2"
    encrypt      = true
    use_lockfile = true
  }
}
