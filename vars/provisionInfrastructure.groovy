#!/usr/bin/env groovy

def call() {
    echo "Starting infrastructure provisioning with Terraform"
    
    withCredentials([
        string(credentialsId: 'aws-access-key', variable: 'AWS_ACCESS_KEY_ID'),
        string(credentialsId: 'aws-secret-key', variable: 'AWS_SECRET_ACCESS_KEY')
    ]) {
        dir('terraform') {
            try {
                // Initialize Terraform
                sh 'terraform init'
                
                // Validate Terraform configuration
                sh 'terraform validate'
                
                // Plan Terraform changes
                sh 'terraform plan -out=tfplan'
                
                // Apply Terraform changes
                sh 'terraform apply -auto-approve tfplan'
                
                // Extract outputs for later use
                def clusterName = sh(script: 'terraform output -raw cluster_name', returnStdout: true).trim()
                def clusterEndpoint = sh(script: 'terraform output -raw cluster_endpoint', returnStdout: true).trim()
                
                // Store outputs as environment variables
                env.EKS_CLUSTER_NAME = clusterName
                env.EKS_CLUSTER_ENDPOINT = clusterEndpoint
                
                echo "Successfully provisioned EKS cluster: ${clusterName}"
                echo "Cluster endpoint: ${clusterEndpoint}"
                
            } catch (Exception e) {
                echo "Error during infrastructure provisioning: ${e.message}"
                throw e
            }
        }
    }
}