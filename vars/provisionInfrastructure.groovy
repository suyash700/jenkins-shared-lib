#!/usr/bin/env groovy

def call() {
    echo "Starting infrastructure provisioning with Terraform"
    
    try {
        // Use AWS credentials properly
        withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', 
                          credentialsId: 'aws-access-key', 
                          accessKeyVariable: 'AWS_ACCESS_KEY_ID', 
                          secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
            
            // Set AWS region
            env.AWS_DEFAULT_REGION = 'eu-north-1'
            
            // Navigate to Terraform directory
            dir('terraform') {
                // Initialize Terraform
                sh 'terraform init -upgrade'
                
                // Create a script to handle CloudWatch Log Group and EKS setup
                writeFile file: 'prepare_terraform.sh', text: '''#!/bin/bash
set -e

# Function to check and handle CloudWatch Log Group
handle_cloudwatch_log_group() {
    if aws logs describe-log-groups --log-group-name-prefix "/aws/eks/easyshop-prod/cluster" | grep -q "logGroupName"; then
        echo "CloudWatch Log Group exists, removing from state and creating override..."
        
        # Remove from state if exists
        terraform state rm module.eks.aws_cloudwatch_log_group.this || true
        
        # Create override file with complete EKS configuration
        cat > override.tf << EOF
module "eks" {
  source  = "terraform-aws-modules/eks/aws"
  version = "~> 18.0"

  cluster_name    = "easyshop-\${var.environment}"
  cluster_version = "1.32"

  vpc_id     = module.vpc.vpc_id
  subnet_ids = module.vpc.private_subnets

  create_cloudwatch_log_group = false
  cluster_enabled_log_types   = []

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
        
        # Force state refresh
        terraform refresh -var-file=terraform.tfvars || true
    fi
}

# Function to check EKS cluster existence
handle_eks_cluster() {
    if aws eks list-clusters | grep -q "easyshop-prod"; then
        echo "EKS cluster exists, preparing for update..."
        export TF_VAR_update_mode=true
    else
        echo "No existing EKS cluster found, preparing for creation..."
        export TF_VAR_update_mode=false
    fi
}

# Main execution
echo "Preparing Terraform environment..."
handle_cloudwatch_log_group
handle_eks_cluster

# Update provider versions
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

                // Make script executable and run
                sh 'chmod +x prepare_terraform.sh && ./prepare_terraform.sh'

                // Re-initialize with upgraded providers
                sh 'terraform init -upgrade'

                // Plan and apply with improved error handling
                sh '''
                # Create plan with complete configuration
                terraform plan -out=tfplan || {
                    echo "Initial plan failed, attempting targeted approach..."
                    terraform plan -target=module.vpc -target=module.eks -out=tfplan
                }

                # Apply if plan exists
                if [ -f "tfplan" ]; then
                    terraform apply -auto-approve tfplan || {
                        echo "Full apply failed, attempting targeted apply..."
                        terraform apply -auto-approve -target=module.vpc -target=module.eks
                    }
                else
                    echo "No plan file found, cannot proceed"
                    exit 1
                fi
                '''
                
                // Get outputs
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
