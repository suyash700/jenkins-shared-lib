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
            shift 2
            local attempt=1
            local cmd="\$@"
            
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
        
        # Check cluster health
        echo "Checking EKS cluster health..."
        CLUSTER_STATUS=\$(aws eks describe-cluster --name ${clusterName} --region ${region} --query 'cluster.status' --output text)
        echo "Cluster status: \$CLUSTER_STATUS"
        
        if [ "\$CLUSTER_STATUS" != "ACTIVE" ]; then
            echo "Warning: Cluster is not in ACTIVE state. Current state: \$CLUSTER_STATUS"
            exit 1
        fi
        
        # Check if Kubernetes resources are ready
        echo "Checking if Kubernetes resources are ready..."
        
        # Check if ingress-nginx is installed
        if ! kubectl get namespace ingress-nginx &>/dev/null; then
            echo "Warning: ingress-nginx namespace not found. Running Terraform apply again may be needed."
            # Don't exit here, continue checking other resources
        else
            echo "Checking ingress-nginx controller status..."
            kubectl -n ingress-nginx get deployment ingress-nginx-controller -o jsonpath="{.status.readyReplicas}" || echo "ingress-nginx controller not found"
        fi
        
        # Check if cert-manager is installed
        if ! kubectl get namespace cert-manager &>/dev/null; then
            echo "Warning: cert-manager namespace not found. Running Terraform apply again may be needed."
            # Don't exit here, continue checking other resources
        else
            echo "Checking cert-manager status..."
            kubectl -n cert-manager get deployment cert-manager -o jsonpath="{.status.readyReplicas}" || echo "cert-manager deployment not found"
        fi
        
        # Wait for deployment to be ready
        echo "Waiting for ${deploymentName} deployment to be ready..."
        if kubectl get deployment -n ${namespace} ${deploymentName} &>/dev/null; then
            kubectl wait --namespace ${namespace} \\
              --for=condition=available deployment/${deploymentName} \\
              --timeout=300s || echo "${deploymentName} deployment may not be ready yet"
        else
            echo "${deploymentName} deployment not found in namespace ${namespace}"
        fi
        
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
        if kubectl get namespace argocd &>/dev/null; then
            retry 3 5 kubectl get applications -n argocd ${deploymentName} -o jsonpath="{.status.sync.status}" || echo "Unable to get ArgoCD application status"
            
            # Get ArgoCD application health with retries
            echo "ArgoCD application health:"
            retry 3 5 kubectl get applications -n argocd ${deploymentName} -o jsonpath="{.status.health.status}" || echo "Unable to get ArgoCD application health"
        else
            echo "ArgoCD namespace not found"
        fi
        
        # Verify all pods are running
        echo "Verifying all pods in ${namespace} namespace:"
        kubectl get pods -n ${namespace}
        
        echo "Deployment verification completed."
    """
}