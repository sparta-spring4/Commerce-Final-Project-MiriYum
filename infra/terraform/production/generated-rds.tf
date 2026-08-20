# __generated__ by Terraform
# Please review these resources and move them into your main configuration files.

# __generated__ by Terraform
resource "aws_db_instance" "production" {
  allocated_storage                     = 20
  allow_major_version_upgrade           = null
  apply_immediately                     = null
  auto_minor_version_upgrade            = true
  availability_zone                     = "ap-northeast-2b"
  backup_retention_period               = 7
  backup_target                         = "region"
  backup_window                         = "15:45-16:15"
  ca_cert_identifier                    = "rds-ca-rsa2048-g1"
  copy_tags_to_snapshot                 = true
  custom_iam_instance_profile           = null
  customer_owned_ip_enabled             = false
  database_insights_mode                = "standard"
  db_name                               = "miriyum"
  db_subnet_group_name                  = "iriyum-prod-db-subnet-group"
  dedicated_log_volume                  = false
  delete_automated_backups              = true
  deletion_protection                   = true
  domain                                = null
  domain_auth_secret_arn                = null
  domain_iam_role_name                  = null
  domain_ou                             = null
  enabled_cloudwatch_logs_exports       = []
  engine                                = "mysql"
  engine_lifecycle_support              = "open-source-rds-extended-support-disabled"
  engine_version                        = "8.4.9"
  final_snapshot_identifier             = null
  iam_database_authentication_enabled   = false
  identifier                            = "miriyum-prod-mysql"
  instance_class                        = "db.t4g.micro"
  iops                                  = 3000
  kms_key_id                            = "arn:aws:kms:ap-northeast-2:579750808837:key/32d54110-8caf-47fd-8865-3a65f3b8795b"
  license_model                         = "general-public-license"
  maintenance_window                    = "mon:18:11-mon:18:41"
  manage_master_user_password           = null
  max_allocated_storage                 = 1000
  monitoring_interval                   = 0
  multi_az                              = false
  network_type                          = "IPV4"
  option_group_name                     = "default:mysql-8-4"
  parameter_group_name                  = "miriyum-prod-mysql-params"
  password                              = null # sensitive
  password_wo                           = null # sensitive
  password_wo_version                   = null
  performance_insights_enabled          = false
  performance_insights_retention_period = 0
  port                                  = 3306
  publicly_accessible                   = false
  region                                = "ap-northeast-2"
  replicate_source_db                   = null
  skip_final_snapshot                   = true
  storage_encrypted                     = true
  storage_throughput                    = 125
  storage_type                          = "gp3"
  tags                                  = {}
  tags_all                              = {}
  upgrade_storage_config                = null
  username                              = "miriyum_admin"
  vpc_security_group_ids                = ["sg-02fe40378345a2228"]
  lifecycle {
    prevent_destroy = true
  }
}
