#!/usr/bin/env groovy

def call() {
    echo "Installing Helm if not already installed"
    
    sh '''
        # Check if Helm is installed
        if ! command -v helm &> /dev/null; then
            echo "Helm not found, installing..."
            
            # Create temporary directory
            mkdir -p /tmp/helm-install
            cd /tmp/helm-install
            
            # Download and install Helm
            curl -fsSL -o get_helm.sh https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3
            chmod 700 get_helm.sh
            ./get_helm.sh
            
            # Clean up
            cd -
            rm -rf /tmp/helm-install
            
            # Verify installation
            helm version
        else
            echo "Helm is already installed"
            helm version
        fi
    '''
}