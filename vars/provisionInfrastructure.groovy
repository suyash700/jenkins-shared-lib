#!/usr/bin/env groovy

def call() {
    echo "Starting infrastructure provisioning with Terraform"
    
    try {
        // Check if Terraform is installed, if not install it
        sh '''
            if ! command -v terraform &> /dev/null; then
                echo "Terraform not found, installing..."
                # Create a directory for Terraform
                mkdir -p /tmp/terraform
                cd /tmp/terraform
                
                # Download Terraform
                wget https://releases.hashicorp.com/terraform/1.5.7/terraform_1.5.7_linux_amd64.zip
                
                # Unzip and move to a directory in PATH
                unzip terraform_1.5.7_linux_amd64.zip
                sudo mv terraform /usr/local/bin/
                
                # Verify installation
                terraform --version
                
                # Clean up
                cd -
                rm -rf /tmp/terraform
            else
                echo "Terraform is already installed"
                terraform --version
            fi
        '''
        
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
                
                // Create a simple script to handle CloudWatch Log Group conflicts and other issues
                writeFile file: 'prepare_terraform.sh', text: '''#!/bin/bash
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

# Check if CloudWatch Log Group exists
echo "Checking for existing CloudWatch Log Group..."
if aws logs describe-log-groups --log-group-name-prefix "/aws/eks/easyshop-prod/cluster" | grep -q "logGroupName"; then
    echo "CloudWatch Log Group already exists, creating override..."
    
    # Create a simple override file
    cat > cloudwatch_override.tf << EOF
# Disable CloudWatch Log Group creation
module "eks" {
  create_cloudwatch_log_group = false
  cluster_enabled_log_types   = []
}
EOF

    # Create the variable definition if it doesn't exist
    if ! grep -q "variable \\"cluster_enabled_log_types\\"" variables.tf; then
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
    
    # Remove the CloudWatch Log Group from Terraform state if it exists
    terraform state list | grep aws_cloudwatch_log_group | xargs -r terraform state rm || true
fi

# Check for VPC limit issues
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

# Update Kubernetes version in the EKS module
if [ -f "main.tf" ]; then
    echo "Updating Kubernetes version in EKS configuration..."
    # Replace Kubernetes version 1.24 with 1.32
    sed -i 's/version = "1.24"/version = "1.32"/g' main.tf
    # If version is specified in a variable file, update that too
    if [ -f "variables.tf" ]; then
        sed -i 's/default     = "1.24"/default     = "1.32"/g' variables.tf
    fi
    echo "Kubernetes version updated to 1.32"
else
    echo "Warning: main.tf not found, could not update Kubernetes version"
fi

# Patch VPC module to fix compatibility issues
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
fi
'''
                
                // Make the script executable and run it
                sh 'chmod +x prepare_terraform.sh && ./prepare_terraform.sh'
                
                // Re-initialize Terraform to ensure modules are properly loaded
                sh 'terraform init -upgrade'
                
                // Plan and apply with simple error handling
                sh '''
                # Try normal plan first
                if [ -f ".reuse_vpc" ]; then
                    echo "Planning with VPC reuse strategy..."
                    terraform plan -target=module.eks -out=tfplan || terraform plan -target=module.eks.aws_eks_cluster.this -out=tfplan
                else
                    # Normal planning
                    terraform plan -out=tfplan || terraform plan -target=module.vpc -target=module.eks.aws_eks_cluster.this -out=tfplan
                fi
                
                # Apply with error handling
                if [ -f "tfplan" ]; then
                    echo "Applying Terraform plan..."
                    terraform apply -auto-approve tfplan || terraform apply -auto-approve -target=module.vpc -target=module.eks.aws_eks_cluster.this
                else
                    echo "No plan file found, cannot apply changes"
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
