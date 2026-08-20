resource "aws_s3_bucket" "production_files" {
  bucket        = var.production_storage_s3_bucket
  force_destroy = false

  tags = {
    Name    = "miriyum-production-files"
    Purpose = "public-store-menu-images"
  }

  lifecycle {
    prevent_destroy = true
  }
}

# S3 어댑터는 versioning enabled/suspended 버킷을 안전하게 정리할 수 없으므로
# 이 전용 버킷에는 versioning 설정을 만들지 않는다(기본 Disabled 상태 유지).
resource "aws_s3_bucket_server_side_encryption_configuration" "production_files" {
  bucket = aws_s3_bucket.production_files.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_public_access_block" "production_files" {
  bucket                  = aws_s3_bucket.production_files.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "production_files" {
  bucket = aws_s3_bucket.production_files.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

# 업로드가 중단된 multipart 객체만 정리한다. 정상 공개 이미지의 보존·삭제는
# FileMetadata 상태 전이와 reconciliation이 결정하므로 Terraform에서 임의 만료하지 않는다.
resource "aws_s3_bucket_lifecycle_configuration" "production_files" {
  bucket = aws_s3_bucket.production_files.id

  rule {
    id     = "abort-incomplete-multipart-uploads"
    status = "Enabled"

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }
}

data "aws_iam_policy_document" "production_files_tls_only" {
  statement {
    sid    = "DenyInsecureTransport"
    effect = "Deny"

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    actions = ["s3:*"]
    resources = [
      aws_s3_bucket.production_files.arn,
      "${aws_s3_bucket.production_files.arn}/*"
    ]

    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_s3_bucket_policy" "production_files_tls_only" {
  bucket = aws_s3_bucket.production_files.id
  policy = data.aws_iam_policy_document.production_files_tls_only.json
}

# 버킷 이름은 민감하지 않지만, ECS task definition에 고정하지 않고 Parameter Store로 주입한다.
resource "aws_ssm_parameter" "production_storage_s3_bucket" {
  name  = "/miriyum/production/storage-s3-bucket"
  type  = "String"
  value = aws_s3_bucket.production_files.bucket
}

resource "aws_iam_role_policy" "production_backend_s3_files" {
  name = "MiriyumProductionS3FileAccess"
  role = "miriyum-prod-ecs-task-role"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "ReadBucketVersioning"
        Effect   = "Allow"
        Action   = ["s3:GetBucketVersioning"]
        Resource = aws_s3_bucket.production_files.arn
      },
      {
        Sid    = "ManagePublicImageObjects"
        Effect = "Allow"
        Action = [
          "s3:GetObject",
          "s3:PutObject",
          "s3:DeleteObject"
        ]
        Resource = [
          "${aws_s3_bucket.production_files.arn}/public/stores/*",
          "${aws_s3_bucket.production_files.arn}/public/menus/*"
        ]
      }
    ]
  })
}

# ECS가 SSM Parameter를 컨테이너 환경변수로 주입할 때는 execution role을 사용한다.
resource "aws_iam_role_policy" "production_task_execution_storage_parameter" {
  name = "MiriyumProductionStorageParameterRead"
  role = "miriyum-prod-ecs-task-execution-role"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "ReadProductionStorageBucketParameter"
        Effect   = "Allow"
        Action   = ["ssm:GetParameters"]
        Resource = aws_ssm_parameter.production_storage_s3_bucket.arn
      }
    ]
  })
}
