def call(Map config = [:]) {
    def clusterName = config.clusterName ?: 'easyshop-prod'
    def region = config.region ?: 'eu-north-1'
    def domainName = config.domainName ?: 'iemafzalhassan.tech'
    def namespace = config.namespace ?: 'easyshop'
    def deploymentName = config.deploymentName ?: 'easyshop'
    
    sh """#!/bin/bash
        set -e
        
        # Configure kubectl to use the EKS cluster
        aws eks update-kubeconfig --name ${clusterName} --region ${region}
        
        # Wait for deployment to be ready
        echo "Waiting for ${deploymentName} deployment to be ready..."
        kubectl wait --namespace ${namespace} \\
          --for=condition=available deployment/${deploymentName} \\
          --timeout=300s || echo "${deploymentName} deployment may not be ready yet"
        
        # Get application URL
        EASYSHOP_URL="https://${deploymentName}.${domainName}"
        
        # Check if application is accessible (with a timeout)
        echo "Checking if application is accessible..."
        timeout 60 bash -c 'until curl -s -o /dev/null -w "%{http_code}" '\$EASYSHOP_URL' | grep -q 200; do echo "Waiting for ${deploymentName} to be accessible..."; sleep 5; done' || echo "${deploymentName} is not accessible yet"
        
        # Get monitoring URLs
        echo "ArgoCD URL: https://argocd.${domainName}"
        echo "Grafana URL: https://grafana.${domainName}"
        echo "Prometheus URL: https://prometheus.${domainName}"
        
        # Get ArgoCD application status
        echo "ArgoCD application status:"
        kubectl get applications -n argocd ${deploymentName} -o jsonpath="{.status.sync.status}" || echo "Unable to get ArgoCD application status"
        
        # Get ArgoCD application health
        echo "ArgoCD application health:"
        kubectl get applications -n argocd ${deploymentName} -o jsonpath="{.status.health.status}" || echo "Unable to get ArgoCD application health"
    """
}