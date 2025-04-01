#!/usr/bin/env groovy

def call() {
    echo "Starting infrastructure provisioning with Terraform"
    
    try {
        withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', 
                          credentialsId: 'aws-access-key', 
                          accessKeyVariable: 'AWS_ACCESS_KEY_ID', 
                          secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
            
            env.AWS_DEFAULT_REGION = 'eu-north-1'
            
            // Setup Terraform backend first
            sh 'chmod +x scripts/setup-terraform-backend.sh'
            sh './scripts/setup-terraform-backend.sh'
            
            dir('terraform') {
                // Update VPC module version and fix deprecated parameters
                sh '''
                    sed -i 's/version = "~> 3.0"/version = "~> 5.0"/' main.tf
                    sed -i 's/vpc = true/domain = "vpc"/' main.tf
                '''
                
                // Handle existing IAM role
                sh '''
                    if aws iam get-role --role-name easyshop-eks-role &>/dev/null; then
                        echo "Role already exists, updating configuration..."
                        sed -i 's/create_role\\s*=\\s*true/create_role = false/' main.tf
                    fi
                '''
                
                // Initialize Terraform
                sh '''
                    terraform init -upgrade
                '''
                
                // Plan and apply with improved error handling
                sh '''
                    terraform plan -var="environment=prod" -var="aws_region=eu-north-1" -out=tfplan
                    terraform apply -auto-approve tfplan
                '''
                
                // Extract and store outputs
                sh '''
                    terraform output -json > terraform_outputs.json
                '''
                
                // Get EKS cluster name
                def eksClusterName = sh(
                    script: 'jq -r .cluster_name.value terraform_outputs.json || echo "easyshop-prod"',
                    returnStdout: true
                ).trim()
                
                env.EKS_CLUSTER_NAME = eksClusterName
                echo "EKS Cluster Name: ${env.EKS_CLUSTER_NAME}"
                
                // Get NGINX Ingress Controller DNS
                def ingressDns = sh(
                    script: 'jq -r .nginx_ingress_hostname.value terraform_outputs.json || echo "not-available"',
                    returnStdout: true
                ).trim()
                
                env.INGRESS_DNS = ingressDns
                echo "NGINX Ingress DNS: ${env.INGRESS_DNS}"
            }
        }
    } catch (Exception e) {
        echo "Error during infrastructure provisioning: ${e.message}"
        throw e
    }
}