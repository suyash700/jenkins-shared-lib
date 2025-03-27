#!/usr/bin/env groovy

def call() {
    echo "Starting environment configuration with Ansible"
    
    withCredentials([
        string(credentialsId: 'aws-access-key', variable: 'AWS_ACCESS_KEY_ID'),
        string(credentialsId: 'aws-secret-key', variable: 'AWS_SECRET_ACCESS_KEY')
    ]) {
        dir('ansible') {
            try {
                // Install required Ansible collections
                sh 'ansible-galaxy collection install kubernetes.core'
                sh 'ansible-galaxy collection install community.general'
                
                // Run Ansible playbook
                sh 'ansible-playbook playbooks/main.yml'
                
                echo "Successfully configured EKS environment"
                
            } catch (Exception e) {
                echo "Error during environment configuration: ${e.message}"
                throw e
            }
        }
    }
}