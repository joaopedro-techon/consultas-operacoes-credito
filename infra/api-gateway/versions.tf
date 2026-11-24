terraform {
  required_version = ">= 1.6.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.73"
    }
  }

  # Backend remoto (S3) fica fora do git: configure com
  # `terraform init -backend-config=backend.hcl` (ver README).
}

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = var.tags
  }
}
