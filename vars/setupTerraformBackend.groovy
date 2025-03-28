#!/usr/bin/env groovy

def call() {
    echo "Setting up Terraform backend infrastructure"
    
    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', 
                      credentialsId: 'aws-access-key', 
                      accessKeyVariable: 'AWS_ACCESS_KEY_ID', 
                      secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        
        sh 'chmod +x scripts/setup-terraform-backend.sh'
        sh './scripts/setup-terraform-backend.sh'
    }
}