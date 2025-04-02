def call(Map config = [:]) {
    def clusterName = config.clusterName ?: 'easyshop-prod'
    def region = config.region ?: 'eu-north-1'
    def terraformDir = config.terraformDir ?: 'terraform'
    def forceRecreate = config.forceRecreate ?: false
    
    // Explicitly pass AWS credentials to the script
    def awsAccessKey = env.AWS_ACCESS_KEY_ID
    def awsSecretKey = env.AWS_SECRET_ACCESS_KEY
    def awsRegion = env.AWS_DEFAULT_REGION
    
    sh """#!/bin/bash
        set -e
        
        # Change to terraform directory
        cd ${terraformDir}
        
        # Explicitly set AWS credentials in the script
        export AWS_ACCESS_KEY_ID='${awsAccessKey}'
        export AWS_SECRET_ACCESS_KEY='${awsSecretKey}'
        export AWS_DEFAULT_REGION='${awsRegion}'
        
        # Create AWS config files with proper permissions
        mkdir -p ~/.aws
        
        cat > ~/.aws/credentials << EOF
[default]
aws_access_key_id = ${awsAccessKey}
aws_secret_access_key = ${awsSecretKey}
EOF
        
        cat > ~/.aws/config << EOF
[default]
region = ${awsRegion}
output = json
EOF
        
        chmod 600 ~/.aws/credentials
        chmod 600 ~/.aws/config
        
        # Verify AWS credentials are working
        echo "Verifying AWS credentials..."
        aws sts get-caller-identity
        
        if [ \$? -ne 0 ]; then
            echo "AWS credentials verification failed. Please check Jenkins credentials configuration."
            exit 1
        fi
        
        # Check if cluster exists
        echo "Checking if cluster ${clusterName} exists..."
        if aws eks describe-cluster --name ${clusterName} --region ${awsRegion} 2>/dev/null; then
            echo "Cluster ${clusterName} already exists."
            
            if [ "${forceRecreate}" = "true" ]; then
                echo "Force recreate enabled. Destroying existing cluster..."
                
                # Configure kubectl to use the existing cluster
                aws eks update-kubeconfig --name ${clusterName} --region ${awsRegion}
                
                # Delete any existing resources that might block deletion
                echo "Cleaning up resources before destroying cluster..."
                kubectl delete svc --all --all-namespaces || true
                kubectl delete ingress --all --all-namespaces || true
                kubectl delete pvc --all --all-namespaces || true
                
                # Initialize Terraform
                terraform init -upgrade
                
                # Destroy existing infrastructure
                terraform destroy -auto-approve -var="aws_access_key=${awsAccessKey}" -var="aws_secret_key=${awsSecretKey}" -var="region=${awsRegion}"
                
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
        terraform apply -auto-approve -var="aws_access_key=${awsAccessKey}" -var="aws_secret_key=${awsSecretKey}" -var="region=${awsRegion}"
        
        # Configure kubectl to use the new cluster
        echo "Configuring kubectl..."
        aws eks update-kubeconfig --name ${clusterName} --region ${awsRegion}
        
        # Verify cluster is accessible
        echo "Verifying cluster access..."
        kubectl get nodes
        
        echo "Infrastructure deployment completed successfully."
    """
}