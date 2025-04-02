def call(Map config = [:]) {
    def clusterName = config.clusterName ?: 'easyshop-prod'
    def region = config.region ?: 'eu-north-1'
    def terraformDir = config.terraformDir ?: 'terraform'
    def forceRecreate = config.forceRecreate ?: false
    
    sh """#!/bin/bash
        set -e
        
        # Change to terraform directory
        cd ${terraformDir}
        
        # Verify AWS credentials are working
        echo "Verifying AWS credentials..."
        aws sts get-caller-identity || {
            echo "AWS credentials verification failed. Attempting to refresh credentials..."
            # Try to refresh credentials if using AWS SSO or role assumption
            aws sso login || true
        }
        
        # Verify credentials again after potential refresh
        aws sts get-caller-identity || {
            echo "AWS credentials still invalid. Please check Jenkins credentials configuration."
            exit 1
        }
        
        # Check if cluster exists
        if aws eks describe-cluster --name ${clusterName} --region ${region} 2>/dev/null; then
            echo "Cluster ${clusterName} already exists."
            
            if [ "${forceRecreate}" = "true" ]; then
                echo "Force recreate enabled. Destroying existing cluster..."
                
                # Configure kubectl to use the existing cluster
                aws eks update-kubeconfig --name ${clusterName} --region ${region}
                
                # Delete any existing resources that might block deletion
                echo "Cleaning up resources before destroying cluster..."
                kubectl delete svc --all --all-namespaces || true
                kubectl delete ingress --all --all-namespaces || true
                kubectl delete pvc --all --all-namespaces || true
                
                # Initialize Terraform
                terraform init -upgrade
                
                # Destroy existing infrastructure
                terraform destroy -auto-approve
                
                # Wait for resources to be fully released
                echo "Waiting for AWS resources to be fully released..."
                sleep 60
            else
                echo "Using existing cluster. Set forceRecreate=true to recreate."
                exit 0
            fi
        else
            echo "Cluster ${clusterName} does not exist. Creating new cluster..."
        fi
        
        # Initialize Terraform
        echo "Initializing Terraform..."
        terraform init -upgrade
        
        # Apply Terraform configuration
        echo "Applying Terraform configuration..."
        terraform apply -auto-approve
        
        # Configure kubectl to use the new cluster
        echo "Configuring kubectl..."
        aws eks update-kubeconfig --name ${clusterName} --region ${region}
        
        # Verify cluster is accessible
        echo "Verifying cluster access..."
        kubectl get nodes
        
        echo "Infrastructure deployment completed successfully."
    """
}