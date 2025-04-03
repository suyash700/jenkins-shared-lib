def call(Map config = [:]) {
    def clusterName = config.clusterName ?: 'easyshop-prod'
    def region = config.region ?: 'eu-north-1'
    def domainName = config.domainName ?: 'iemafzalhassan.tech'
    def namespace = config.namespace ?: 'easyshop'
    def deploymentName = config.deploymentName ?: 'easyshop'
    
    sh """#!/bin/bash
        set -e
        
        # Function to retry a command with exponential backoff
        function retry {
            local max_attempts=\$1
            local delay=\$2
            local attempt=1
            local cmd="\${@:3}"
            
            until \$cmd; do
                if (( attempt == max_attempts )); then
                    echo "Command failed after \$max_attempts attempts: \$cmd"
                    return 1
                fi
                
                echo "Attempt \$attempt failed. Retrying in \$delay seconds..."
                sleep \$delay
                attempt=\$((attempt + 1))
                delay=\$((delay * 2))
            done
            
            return 0
        }
        
        # Configure kubectl to use the EKS cluster with retries
        echo "Configuring kubectl to use the EKS cluster..."
        retry 5 5 aws eks update-kubeconfig --name ${clusterName} --region ${region}
        
        # Verify cluster is accessible
        echo "Verifying EKS cluster is accessible..."
        retry 5 5 kubectl get nodes
        
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
        
        # Get ArgoCD application status with retries
        echo "ArgoCD application status:"
        retry 3 5 kubectl get applications -n argocd ${deploymentName} -o jsonpath="{.status.sync.status}" || echo "Unable to get ArgoCD application status"
        
        # Get ArgoCD application health with retries
        echo "ArgoCD application health:"
        retry 3 5 kubectl get applications -n argocd ${deploymentName} -o jsonpath="{.status.health.status}" || echo "Unable to get ArgoCD application health"
        
        # Verify all pods are running
        echo "Verifying all pods in ${namespace} namespace:"
        kubectl get pods -n ${namespace}
        
        echo "Deployment verification completed."
    """
}