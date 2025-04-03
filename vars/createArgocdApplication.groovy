def call(Map config = [:]) {
    def clusterName = config.clusterName ?: 'easyshop-prod'
    def region = config.region ?: 'eu-north-1'
    def workspace = config.workspace ?: env.WORKSPACE
    
    sh """#!/bin/bash
        set -e
        
        # Configure kubectl to use the EKS cluster
        aws eks update-kubeconfig --name ${clusterName} --region ${region}
        
        # Create easyshop namespace if it doesn't exist
        if ! kubectl get namespace easyshop &>/dev/null; then
            echo "Creating easyshop namespace..."
            kubectl create namespace easyshop
        fi
        
        # Apply the ArgoCD application from the repository file
        echo "Creating ArgoCD application for EasyShop..."
        kubectl apply -f ${workspace}/kubernetes/argocd/application.yaml
    """
}