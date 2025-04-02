def call(Map config = [:]) {
    def clusterName = config.clusterName ?: 'easyshop-prod'
    def region = config.region ?: 'eu-north-1'
    def terraformDir = config.terraformDir ?: 'terraform'
    def forceRecreate = config.forceRecreate ?: true
    
    sh """
        cd ${terraformDir}
        
        # Check if cluster already exists
        if aws eks describe-cluster --name ${clusterName} --region ${region} &> /dev/null; then
            echo "Cluster ${clusterName} already exists."
            
            if [ "${forceRecreate}" = "true" ]; then
                echo "Force recreate enabled. Destroying existing cluster..."
                
                # Get current kubeconfig to ensure we can clean up resources properly
                aws eks update-kubeconfig --name ${clusterName} --region ${region}
                
                # Delete all namespaces except kube-system, default, and kube-public
                for ns in \$(kubectl get namespaces -o jsonpath='{.items[*].metadata.name}' | tr ' ' '\\n' | grep -v -E 'kube-system|default|kube-public'); do
                    echo "Deleting namespace: \$ns"
                    kubectl delete namespace \$ns --wait=false
                done
                
                # Initialize Terraform
                terraform init
                
                # Destroy existing infrastructure
                terraform destroy -auto-approve
                
                # Wait a bit to ensure AWS resources are fully released
                echo "Waiting for AWS resources to be fully released..."
                sleep 60
            else
                echo "Using existing cluster. Skipping recreation."
                aws eks update-kubeconfig --name ${clusterName} --region ${region}
                return
            fi
        else
            echo "Cluster ${clusterName} does not exist. Creating new cluster..."
        fi
        
        # Initialize and apply Terraform
        terraform init
        terraform apply -auto-approve -var="cluster_name=${clusterName}" -var="region=${region}"
        
        # Update kubeconfig
        aws eks update-kubeconfig --name ${clusterName} --region ${region}
        
        # Verify cluster is accessible
        kubectl cluster-info
        
        # Create necessary namespaces if they don't exist
        kubectl create namespace argocd --dry-run=client -o yaml | kubectl apply -f -
        kubectl create namespace easyshop --dry-run=client -o yaml | kubectl apply -f -
        kubectl create namespace monitoring --dry-run=client -o yaml | kubectl apply -f -
    """
}