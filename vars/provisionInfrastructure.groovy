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
                // Initialize Terraform
                sh 'terraform init -upgrade'
                
                // Plan and apply with error handling
                sh '''#!/bin/bash
                set -e
                
                # Remove any existing plan
                rm -f tfplan || true
                
                # Create new plan
                terraform plan -var="environment=prod" -out=tfplan || {
                    echo "Initial plan failed, attempting targeted approach..."
                    terraform plan -var="environment=prod" -target=module.vpc -target=module.eks -out=tfplan
                }
                
                # Apply the plan
                if [ -f "tfplan" ]; then
                    terraform apply -auto-approve tfplan || {
                        echo "Full apply failed, attempting targeted apply..."
                        terraform apply -auto-approve -var="environment=prod" -target=module.vpc -target=module.eks
                    }
                else
                    echo "No plan file found, cannot proceed"
                    exit 1
                fi
                '''
                
                // Get cluster name from outputs
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
