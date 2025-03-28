#!/usr/bin/env groovy

def call() {
    echo "Starting infrastructure provisioning with Terraform"
    
    try {
        withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', 
                          credentialsId: 'aws-access-key', 
                          accessKeyVariable: 'AWS_ACCESS_KEY_ID', 
                          secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
            
            env.AWS_DEFAULT_REGION = 'eu-north-1'
            
            dir('terraform') {
                sh 'terraform init -upgrade'
                
                writeFile file: 'prepare_terraform.sh', text: '''#!/bin/bash
set -e

handle_cloudwatch_log_group() {
    local log_group="/aws/eks/easyshop-prod/cluster"
    
    if aws logs describe-log-groups --log-group-name-prefix "$log_group" | grep -q "logGroupName"; then
        echo "Found existing CloudWatch Log Group, deleting it..."
        aws logs delete-log-group --log-group-name "$log_group"
        echo "Waiting for deletion to complete..."
        sleep 10
        
        echo "Removing from Terraform state..."
        terraform state rm module.eks.aws_cloudwatch_log_group.this || true
    fi
    
    cat > main.tf << EOF
module "eks" {
  source  = "terraform-aws-modules/eks/aws"
  version = "~> 18.0"

  cluster_name    = "easyshop-\${var.environment}"
  cluster_version = "1.32"

  vpc_id     = module.vpc.vpc_id
  subnet_ids = module.vpc.private_subnets

  create_cloudwatch_log_group = true
  cloudwatch_log_group_retention_in_days = 30
  cluster_enabled_log_types  = ["api", "audit", "authenticator", "controllerManager", "scheduler"]

  eks_managed_node_group_defaults = {
    disk_size      = 50
    instance_types = ["t3.medium"]
  }

  eks_managed_node_groups = {
    app_nodes = {
      min_size     = 2
      max_size     = 5
      desired_size = 2
      instance_types = ["t3.medium"]
      capacity_type  = "ON_DEMAND"
    }
  }

  tags = {
    Environment = var.environment
    Project     = "EasyShop"
    Terraform   = "true"
  }
}
EOF
}

handle_eks_cluster() {
    if aws eks list-clusters | grep -q "easyshop-prod"; then
        echo "EKS cluster exists, preparing for update..."
        export TF_VAR_update_mode=true
    else
        echo "No existing EKS cluster found, preparing for creation..."
        export TF_VAR_update_mode=false
    fi
}

echo "Preparing Terraform environment..."
handle_cloudwatch_log_group
handle_eks_cluster

cat > versions.tf << EOF
terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 4.0"
    }
  }
  required_version = ">= 1.0"
}
EOF
'''

                sh 'chmod +x prepare_terraform.sh && ./prepare_terraform.sh'
                sh 'terraform init -upgrade'
                
                sh '''#!/bin/bash
                terraform plan -out=tfplan || {
                    echo "Initial plan failed, attempting targeted approach..."
                    terraform plan -target=module.eks -out=tfplan
                }
                
                if [ -f "tfplan" ]; then
                    terraform apply -auto-approve tfplan || {
                        echo "Full apply failed, attempting targeted apply..."
                        terraform apply -auto-approve -target=module.eks
                    }
                else
                    echo "No plan file found, cannot proceed"
                    exit 1
                fi
                '''
                
                def eksClusterName = sh(script: 'terraform output -raw cluster_name || echo "easyshop-cluster"', returnStdout: true).trim()
                env.EKS_CLUSTER_NAME = eksClusterName
                
                echo "EKS Cluster Name: ${env.EKS_CLUSTER_NAME}"
            }
        }
    } catch (Exception e) {
        echo "Error during infrastructure provisioning: ${e.message}"
        throw e
    }
}
