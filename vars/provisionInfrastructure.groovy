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
                // Initialize Terraform with specific provider versions and patch VPC module
                sh '''
                    # Create a versions.tf file to lock provider versions
                    cat > versions.tf << EOF
terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 4.0"  # Use AWS provider v4.x which is compatible with the modules
    }
  }
  required_version = ">= 1.0"
}
EOF
                    
                    # Initialize with upgrade to apply version constraints
                    terraform init -upgrade
                    
                    # Create a patch script to fix VPC module compatibility issues
                    cat > patch_vpc_module.sh << 'EOF'
#!/bin/bash
# Find the VPC module file
VPC_MAIN_TF=".terraform/modules/vpc/main.tf"

if [ -f "$VPC_MAIN_TF" ]; then
    echo "Patching VPC module to remove deprecated arguments..."
    # Remove enable_classiclink arguments
    sed -i '/enable_classiclink/d' "$VPC_MAIN_TF"
    # Remove enable_classiclink_dns_support arguments
    sed -i '/enable_classiclink_dns_support/d' "$VPC_MAIN_TF"
    echo "VPC module patched successfully"
else
    echo "VPC module file not found at $VPC_MAIN_TF"
    exit 1
fi
EOF
                    
                    # Make the patch script executable
                    chmod +x patch_vpc_module.sh
                    
                    # Run the patch script
                    ./patch_vpc_module.sh
                    
                    # Update Kubernetes version in the EKS module
                    if [ -f "main.tf" ]; then
                        echo "Updating Kubernetes version in EKS configuration..."
                        # Replace Kubernetes version 1.24 with 1.28
                        sed -i 's/version = "1.24"/version = "1.28"/g' main.tf
                        # If version is specified in a variable file, update that too
                        if [ -f "variables.tf" ]; then
                            sed -i 's/default     = "1.24"/default     = "1.28"/g' variables.tf
                        fi
                        echo "Kubernetes version updated to 1.28"
                    else
                        echo "Warning: main.tf not found, could not update Kubernetes version"
                    fi
                    
                    # Handle existing CloudWatch Log Group
                    echo "Checking for existing CloudWatch Log Group..."
                    if aws logs describe-log-groups --log-group-name-prefix "/aws/eks/easyshop-prod/cluster" | grep -q "logGroupName"; then
                        echo "CloudWatch Log Group already exists, modifying Terraform configuration..."
                        
                        # Find the EKS module file that contains the CloudWatch Log Group resource
                        EKS_MODULE_FILE=".terraform/modules/eks/main.tf"
                        
                        if [ -f "$EKS_MODULE_FILE" ]; then
                            # Create a backup of the original file
                            cp "$EKS_MODULE_FILE" "${EKS_MODULE_FILE}.bak"
                            
                            # Modify the CloudWatch Log Group resource to use create_before_destroy lifecycle policy
                            # This helps with the "already exists" error
                            sed -i '/resource "aws_cloudwatch_log_group" "this"/,/}/c\\
resource "aws_cloudwatch_log_group" "this" {\
  name              = local.create && var.create_cloudwatch_log_group ? "/aws/eks/${var.cluster_name}/cluster" : null\
  retention_in_days = var.cloudwatch_log_group_retention_in_days\
  kms_key_id        = var.cloudwatch_log_group_kms_key_id\
  tags              = var.tags\
\
  # Prevent race condition with resource deletion\
  lifecycle {\
    prevent_destroy = true\
    ignore_changes = [name]\
  }\
}' "$EKS_MODULE_FILE"
                            
                            echo "Modified CloudWatch Log Group resource in EKS module"
                        else
                            echo "EKS module file not found at $EKS_MODULE_FILE"
                        fi
                    else
                        echo "CloudWatch Log Group does not exist yet"
                    fi
                '''
                
                // Plan Terraform changes with target to exclude problematic resources
                sh '''
                    # Create a plan excluding the CloudWatch Log Group if it exists
                    if aws logs describe-log-groups --log-group-name-prefix "/aws/eks/easyshop-prod/cluster" | grep -q "logGroupName"; then
                        terraform plan -target="module.vpc" -target="module.eks.aws_eks_cluster.this" -out=tfplan
                    else
                        terraform plan -out=tfplan
                    fi
                '''
                
                // Apply Terraform changes
                sh 'terraform apply -auto-approve tfplan'
                
                // Get outputs if needed
                def eksClusterName = sh(script: 'terraform output -raw eks_cluster_name || echo "eks-cluster"', returnStdout: true).trim()
                env.EKS_CLUSTER_NAME = eksClusterName
                
                echo "EKS Cluster Name: ${env.EKS_CLUSTER_NAME}"
            }
        }
    } catch (Exception e) {
        echo "Error during infrastructure provisioning: ${e.message}"
        throw e
    }
}
