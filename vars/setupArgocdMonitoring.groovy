def call(Map config = [:]) {
    def clusterName = config.clusterName ?: 'easyshop-prod'
    def region = config.region ?: 'eu-north-1'
    def domainName = config.domainName ?: 'iemafzalhassan.tech'
    def workspace = config.workspace ?: env.WORKSPACE
    
    sh """#!/bin/bash
        set -e
        
        # Configure kubectl to use the EKS cluster
        aws eks update-kubeconfig --name ${clusterName} --region ${region}
        
        # Create monitoring namespace if it doesn't exist
        if ! kubectl get namespace monitoring &>/dev/null; then
            echo "Creating monitoring namespace..."
            kubectl create namespace monitoring
        fi
        
        # Apply the monitoring configuration
        echo "Setting up ArgoCD monitoring..."
        kubectl apply -f ${workspace}/kubernetes/argocd/monitoring.yaml
        
        # Wait for Prometheus and Grafana to be ready (with a timeout)
        echo "Waiting for monitoring components to be ready..."
        kubectl wait --namespace monitoring \\
            --for=condition=ready pod \\
            --selector=app=grafana \\
            --timeout=300s || echo "Grafana pods may not be ready yet, continuing anyway"
        
        echo "ArgoCD monitoring setup complete"
        echo "Grafana URL: https://grafana.${domainName}"
        echo "Default username: admin"
        echo "Default password: EasyShopAdmin123"
    """
}