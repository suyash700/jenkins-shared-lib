def call(Map config = [:]) {
    def clusterName = config.clusterName ?: 'easyshop-prod'
    def region = config.region ?: 'eu-north-1'
    def terraformDir = config.terraformDir ?: 'terraform'
    def forceRecreate = config.forceRecreate ?: false
    
    // Use withCredentials block to ensure AWS credentials are available
    withCredentials([
        string(credentialsId: 'aws-credentials', variable: 'AWS_CREDENTIALS'),
        usernamePassword(credentialsId: 'aws-credentials', usernameVariable: 'AWS_ACCESS_KEY', passwordVariable: 'AWS_SECRET_KEY')
    ]) {
        sh """#!/bin/bash
            set -e
            
            # Change to terraform directory
            cd ${terraformDir}
            
            # Explicitly set AWS credentials in the script
            export AWS_ACCESS_KEY_ID='${AWS_ACCESS_KEY}'
            export AWS_SECRET_ACCESS_KEY='${AWS_SECRET_KEY}'
            export AWS_DEFAULT_REGION='${region}'
            
            # Create AWS config files with proper permissions
            mkdir -p ~/.aws
            
            cat > ~/.aws/credentials << EOF
[default]
aws_access_key_id = ${AWS_ACCESS_KEY}
aws_secret_access_key = ${AWS_SECRET_KEY}
EOF
            
            cat > ~/.aws/config << EOF
[default]
region = ${region}
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
                    
                    # Create terraform.tfvars file
                    cat > terraform.tfvars << EOF
aws_access_key = "${AWS_ACCESS_KEY}"
aws_secret_key = "${AWS_SECRET_KEY}"
region = "${region}"
EOF
                    
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
            
            # Create terraform.tfvars file
            cat > terraform.tfvars << EOF
aws_access_key = "${AWS_ACCESS_KEY}"
aws_secret_key = "${AWS_SECRET_KEY}"
region = "${region}"
EOF
            
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
}