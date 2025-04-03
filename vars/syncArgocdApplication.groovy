def call(Map config = [:]) {
    def clusterName = config.clusterName ?: 'easyshop-prod'
    def region = config.region ?: 'eu-north-1'
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
        
        # Install ArgoCD CLI if not already installed
        if ! command -v argocd &> /dev/null; then
            echo "Installing ArgoCD CLI..."
            curl -sSL -o argocd https://github.com/argoproj/argo-cd/releases/latest/download/argocd-\$(uname -s | tr '[:upper:]' '[:lower:]')-\$(uname -m | sed 's/x86_64/amd64/')
            chmod +x argocd
            mkdir -p \$HOME/bin
            mv argocd \$HOME/bin/
            export PATH=\$HOME/bin:\$PATH
        fi
        
        # Configure kubectl to use the EKS cluster
        aws eks update-kubeconfig --name ${clusterName} --region ${region}
        
        # Get ArgoCD admin password with retries
        echo "Retrieving ArgoCD admin password..."
        ARGOCD_PASSWORD=""
        
        for i in {1..5}; do
            TEMP_PASSWORD=\$(kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath="{.data.password}" | base64 -d 2>/dev/null)
            if [ -n "\$TEMP_PASSWORD" ]; then
                ARGOCD_PASSWORD=\$TEMP_PASSWORD
                echo "ArgoCD password retrieved successfully."
                break
            elif [ -f "${workspace}/credentials/argocd-credentials.txt" ]; then
                TEMP_PASSWORD=\$(grep "ArgoCD admin password" ${workspace}/credentials/argocd-credentials.txt | cut -d ":" -f2 | tr -d " ")
                if [ -n "\$TEMP_PASSWORD" ] && [ "\$TEMP_PASSWORD" != "UNKNOWN" ]; then
                    ARGOCD_PASSWORD=\$TEMP_PASSWORD
                    echo "ArgoCD password retrieved from credentials file."
                    break
                fi
            fi
            
            echo "Attempt \$i: Failed to retrieve ArgoCD password. Waiting 10 seconds before retry..."
            sleep 10
            
            if [ \$i -eq 5 ]; then
                echo "Error: Could not retrieve ArgoCD password after multiple attempts."
                exit 1
            fi
        done
        
        # Start port forwarding in the background for reliable access
        echo "Starting port forwarding for ArgoCD server..."
        kubectl port-forward svc/argocd-server -n argocd 8080:443 &
        PORT_FORWARD_PID=\$!
        
        # Give port forwarding time to establish
        sleep 5
        
        # Login to ArgoCD with retries
        echo "Logging in to ArgoCD..."
        retry 5 5 argocd login --insecure --username admin --password "\$ARGOCD_PASSWORD" localhost:8080
        
        # Sync the application with retries
        echo "Syncing EasyShop application..."
        retry 3 10 argocd app sync easyshop --timeout 300
        
        # Wait for the application to be healthy with a longer timeout
        echo "Waiting for EasyShop application to be healthy..."
        argocd app wait easyshop --health --timeout 600 || {
            echo "Warning: EasyShop application may not be fully healthy yet."
            echo "Current application status:"
            argocd app get easyshop
        }
        
        # Kill port-forwarding
        echo "Cleaning up port forwarding..."
        kill \$PORT_FORWARD_PID || true
        
        echo "ArgoCD sync process completed."
    """
}