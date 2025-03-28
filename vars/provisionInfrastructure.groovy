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
                // Update VPC module version in main.tf
                sh '''
                    sed -i 's/version = "~> 3.0"/version = "~> 5.0"/' main.tf
                    sed -i 's/vpc = true/domain = "vpc"/' main.tf
                '''
                
                // Modify the IAM role configuration to handle existing role
                sh '''
                    # Check if the role already exists and modify the configuration
                    if aws iam get-role --role-name easyshop-eks-role &>/dev/null; then
                        echo "Role already exists, updating configuration..."
                        sed -i 's/create_role\\s*=\\s*true/create_role = false/' main.tf
                    fi
                '''
                
                // Initialize Terraform with clean state
                sh '''
                    rm -rf .terraform
                    rm -f .terraform.lock.hcl
                    terraform init -upgrade
                '''
                
                // Plan and apply with improved error handling
                sh '''#!/bin/bash
                set -e
                
                # Remove any existing plan
                rm -f tfplan || true
                
                # Create new plan with detailed output
                terraform plan \
                    -var="environment=prod" \
                    -var="aws_region=eu-north-1" \
                    -detailed-exitcode -out=tfplan || code=$?
                
                if [ "$code" -eq 2 ]; then
                    echo "Changes detected, proceeding with apply..."
                    terraform apply -auto-approve tfplan
                elif [ "$code" -eq 0 ]; then
                    echo "No changes required"
                else
                    echo "Plan failed, attempting targeted approach..."
                    # Skip the IAM role in the targeted approach
                    terraform plan \
                        -var="environment=prod" \
                        -var="aws_region=eu-north-1" \
                        -target=module.vpc \
                        -target=module.eks \
                        -out=tfplan && \
                    terraform apply -auto-approve tfplan
                fi
                '''
                
                // Verify and export cluster info
                sh '''
                    terraform refresh
                    terraform output -json > terraform_outputs.json
                '''
                
                def eksClusterName = sh(
                    script: 'jq -r .cluster_name.value terraform_outputs.json || echo "easyshop-cluster"',
                    returnStdout: true
                ).trim()
                
                env.EKS_CLUSTER_NAME = eksClusterName
                echo "EKS Cluster Name: ${env.EKS_CLUSTER_NAME}"
            }
        }
    } catch (Exception e) {
        echo "Error during infrastructure provisioning: ${e.message}"
        throw e
    }
}