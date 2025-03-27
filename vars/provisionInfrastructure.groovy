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
                '''
                
                // Plan Terraform changes
                sh 'terraform plan -out=tfplan'
                
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
