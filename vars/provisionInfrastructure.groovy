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
                // Check if infrastructure already exists
                sh '''
                    # Check if EKS cluster already exists
                    echo "Checking for existing EKS cluster..."
                    CLUSTER_EXISTS=$(aws eks list-clusters | grep -c "easyshop-prod" || true)
                    
                    if [ "$CLUSTER_EXISTS" -gt 0 ]; then
                        echo "EKS cluster already exists. Will update existing infrastructure."
                        export TF_VAR_update_mode=true
                    else
                        echo "No existing EKS cluster found. Will create new infrastructure."
                        export TF_VAR_update_mode=false
                    fi
                '''
                
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
                    
                    # Create a custom module override for CloudWatch Log Group
                    echo "Creating CloudWatch Log Group override..."
                    
                    # Check for existing CloudWatch Log Group
                    echo "Checking for existing CloudWatch Log Group..."
                    if aws logs describe-log-groups --log-group-name-prefix "/aws/eks/easyshop-prod/cluster" | grep -q "logGroupName"; then
                        echo "CloudWatch Log Group already exists, creating variable override..."
                        
                        # Create a variable file to disable CloudWatch Log Group creation
                        cat > disable_cloudwatch.auto.tfvars << EOF
# Disable CloudWatch Log Group creation by setting empty log types
cluster_enabled_log_types = []
create_cloudwatch_log_group = false
EOF
                        
                        # Create the variable definition if it doesn't exist
                        if ! grep -q "variable \"cluster_enabled_log_types\"" variables.tf; then
                            cat >> variables.tf << EOF

variable "cluster_enabled_log_types" {
  description = "A list of the desired control plane logs to enable"
  type        = list(string)
  default     = ["api", "audit", "authenticator", "controllerManager", "scheduler"]
}

variable "create_cloudwatch_log_group" {
  description = "Determines whether a CloudWatch log group is created for each enabled log type"
  type        = bool
  default     = true
}
EOF
                        fi
                        
                        # Patch the main.tf file to use our variables
                        if grep -q "cluster_enabled_log_types" main.tf; then
                            sed -i 's/cluster_enabled_log_types = \[.*\]/cluster_enabled_log_types = var.cluster_enabled_log_types/g' main.tf
                            
                            # Add create_cloudwatch_log_group parameter if it doesn't exist
                            if ! grep -q "create_cloudwatch_log_group" main.tf; then
                                sed -i '/module "eks" {/a\\n  create_cloudwatch_log_group = var.create_cloudwatch_log_group' main.tf
                            fi
                        fi
                    else
                        echo "CloudWatch Log Group does not exist yet"
                    fi
                    
                    # Check for VPC limit and handle existing VPCs
                    echo "Checking for VPC limit issues..."
                    VPC_COUNT=$(aws ec2 describe-vpcs | grep -c "VpcId" || true)
                    echo "Current VPC count: $VPC_COUNT"
                    
                    if [ "$VPC_COUNT" -ge 5 ]; then
                        echo "WARNING: You are near or at the VPC limit. Checking for unused VPCs to clean up..."
                        
                        # List VPCs that might be from previous runs
                        UNUSED_VPCS=$(aws ec2 describe-vpcs --filters "Name=tag:Project,Values=EasyShop" | jq -r '.Vpcs[].VpcId' || true)
                        
                        if [ ! -z "$UNUSED_VPCS" ]; then
                            echo "Found potentially unused EasyShop VPCs. Please clean them up manually:"
                            echo "$UNUSED_VPCS"
                            
                            # Create a flag file to indicate we need to import existing VPC
                            echo "Setting up to reuse existing VPC if possible..."
                            touch .reuse_vpc
                        fi
                    fi
                '''
                
                // Import existing resources to state if they exist
                sh '''
                    # Check for existing CloudWatch Log Group
                    echo "Checking for existing CloudWatch Log Group..."
                    if aws logs describe-log-groups --log-group-name-prefix "/aws/eks/easyshop-prod/cluster" | grep -q "logGroupName"; then
                        echo "CloudWatch Log Group already exists, removing from Terraform state..."
                        
                        # Remove the CloudWatch Log Group from Terraform state if it exists
                        terraform state list | grep aws_cloudwatch_log_group | xargs -r terraform state rm || true
                    fi
                    
                    # Re-initialize Terraform to ensure modules are properly loaded
                    echo "Re-initializing Terraform..."
                    terraform init -upgrade
                '''
                
                // Plan Terraform changes with a different approach for existing resources
                sh '''
                    # Create a plan with proper targeting to avoid conflicts
                    echo "Planning infrastructure deployment with conflict avoidance..."
                    
                    # If we're at VPC limit, try to target just the EKS cluster
                    if [ -f ".reuse_vpc" ]; then
                        echo "Planning with VPC reuse strategy..."
                        terraform plan -target=module.eks -out=tfplan || {
                            echo "Plan failed, trying with minimal targeting..."
                            terraform plan -target=module.eks.aws_eks_cluster.this -out=tfplan
                        }
                    else
                        # Normal planning
                        terraform plan -out=tfplan || {
                            echo "Plan failed, trying with targeted approach..."
                            terraform plan -target=module.vpc -target=module.eks.aws_eks_cluster.this -out=tfplan
                        }
                    fi
                '''
                
                // Apply Terraform changes
                sh '''
                    # Apply with error handling
                    if [ -f "tfplan" ]; then
                        echo "Applying Terraform plan..."
                        terraform apply -auto-approve tfplan || {
                            echo "Apply failed, trying with targeted approach..."
                            # If apply fails, try a more targeted approach
                            terraform apply -auto-approve -target=module.vpc -target=module.eks.aws_eks_cluster.this
                        }
                    else
                        echo "No plan file found, cannot apply changes"
                        exit 1
                    fi
                '''
                
                // Get outputs if needed
                def eksClusterName = sh(script: 'terraform output -raw cluster_name || echo "eks-cluster"', returnStdout: true).trim()
                env.EKS_CLUSTER_NAME = eksClusterName
                
                echo "EKS Cluster Name: ${env.EKS_CLUSTER_NAME}"
            }
        }
    } catch (Exception e) {
        echo "Error during infrastructure provisioning: ${e.message}"
        throw e
    }
}
