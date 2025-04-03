def call(Map config = [:]) {
    def clusterName = config.clusterName ?: 'easyshop-prod'
    def region = config.region ?: 'eu-north-1'
    def domainName = config.domainName ?: 'iemafzalhassan.tech'
    def workspace = config.workspace ?: env.WORKSPACE
    
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
        
        # Configure kubectl to use the EKS cluster
        echo "Configuring kubectl to use the EKS cluster..."
        retry 3 5 aws eks update-kubeconfig --name ${clusterName} --region ${region}
        
        # Create ArgoCD namespace if it doesn't exist
        if ! kubectl get namespace argocd &>/dev/null; then
            echo "Creating ArgoCD namespace..."
            kubectl create namespace argocd
        fi
        
        # Install ArgoCD using Helm
        echo "Installing ArgoCD..."
        retry 3 5 helm repo add argo https://argoproj.github.io/argo-helm || true
        retry 3 5 helm repo update
        
        # Check if ArgoCD is already installed
        if ! helm status argocd -n argocd &>/dev/null; then
            echo "Installing ArgoCD for the first time..."
            retry 3 10 helm install argocd argo/argo-cd \\
                --namespace argocd \\
                --set server.service.type=ClusterIP \\
                --set server.ingress.enabled=true \\
                --set server.ingress.ingressClassName=nginx \\
                --set server.ingress.hosts[0]=argocd.${domainName} \\
                --set server.ingress.tls[0].hosts[0]=argocd.${domainName} \\
                --set server.ingress.tls[0].secretName=argocd-tls \\
                --set server.ingress.annotations."cert-manager\\.io/cluster-issuer"=letsencrypt-prod \\
                --set controller.metrics.enabled=true \\
                --set server.metrics.enabled=true \\
                --set repoServer.metrics.enabled=true
        else
            # Upgrade ArgoCD if already installed
            echo "Upgrading existing ArgoCD installation..."
            retry 3 10 helm upgrade argocd argo/argo-cd \\
                --namespace argocd \\
                --set server.service.type=ClusterIP \\
                --set server.ingress.enabled=true \\
                --set server.ingress.ingressClassName=nginx \\
                --set server.ingress.hosts[0]=argocd.${domainName} \\
                --set server.ingress.tls[0].hosts[0]=argocd.${domainName} \\
                --set server.ingress.tls[0].secretName=argocd-tls \\
                --set server.ingress.annotations."cert-manager\\.io/cluster-issuer"=letsencrypt-prod \\
                --set controller.metrics.enabled=true \\
                --set server.metrics.enabled=true \\
                --set repoServer.metrics.enabled=true
        fi
        
        # Wait for ArgoCD to be ready with retries
        echo "Waiting for ArgoCD to be ready..."
        retry 5 30 "kubectl wait --namespace argocd \\
            --for=condition=ready pod \\
            --selector=app.kubernetes.io/name=argocd-server \\
            --timeout=60s"
        
        # Store ArgoCD admin password with error handling
        echo "Retrieving ArgoCD admin password..."
        mkdir -p ${workspace}/credentials
        
        # Try multiple times to get the password
        for i in {1..5}; do
            ARGOCD_PASSWORD=\$(kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath="{.data.password}" | base64 -d 2>/dev/null)
            if [ -n "\$ARGOCD_PASSWORD" ]; then
                echo "ArgoCD admin password: \$ARGOCD_PASSWORD" > ${workspace}/credentials/argocd-credentials.txt
                echo "ArgoCD URL: https://argocd.${domainName}" >> ${workspace}/credentials/argocd-credentials.txt
                echo "ArgoCD password retrieved successfully."
                break
            else
                echo "Attempt \$i: Failed to retrieve ArgoCD password. Waiting 10 seconds before retry..."
                sleep 10
            fi
            
            if [ \$i -eq 5 ]; then
                echo "Warning: Could not retrieve ArgoCD password after multiple attempts."
                echo "ArgoCD admin password: UNKNOWN" > ${workspace}/credentials/argocd-credentials.txt
                echo "ArgoCD URL: https://argocd.${domainName}" >> ${workspace}/credentials/argocd-credentials.txt
            fi
        done
        
        echo "ArgoCD installation complete."
    """
}