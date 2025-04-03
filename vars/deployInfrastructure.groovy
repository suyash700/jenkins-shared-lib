def call(Map config = [:]) {
    def clusterName = config.clusterName ?: 'easyshop-prod'
    def region = config.region ?: 'eu-north-1'
    def terraformDir = config.terraformDir ?: 'terraform'
    def forceRecreate = config.forceRecreate ?: false
    def skipOnMissingCredentials = config.skipOnMissingCredentials ?: false
    
    // Check if AWS credentials are available in the environment
    if (env.AWS_ACCESS_KEY_ID == null || env.AWS_SECRET_ACCESS_KEY == null) {
        if (skipOnMissingCredentials) {
            echo "AWS credentials not available. Skipping infrastructure deployment."
            return
        } else {
            error "AWS credentials are required for infrastructure deployment. Please configure 'aws-access-key' in Jenkins or set AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY environment variables."
        }
    }
    
    // Use environment variables directly instead of withCredentials
    sh """#!/bin/bash
        set -e
        
        # Change to project root directory
        cd ${WORKSPACE}
        
        # Make bootstrap script executable
        chmod +x scripts/terraform-bootstrap.sh
        
        # Run the bootstrap script to create S3 bucket and DynamoDB table
        echo "Setting up Terraform backend infrastructure..."
        ./scripts/terraform-bootstrap.sh
        
        # Change to terraform directory
        cd ${terraformDir}
        
        # Simplified AWS configuration
        export AWS_ACCESS_KEY_ID='${env.AWS_ACCESS_KEY_ID}'
        export AWS_SECRET_ACCESS_KEY='${env.AWS_SECRET_ACCESS_KEY}'
        export AWS_DEFAULT_REGION='${region}'
        export TF_VAR_aws_access_key='${env.AWS_ACCESS_KEY_ID}'
        export TF_VAR_aws_secret_key='${env.AWS_SECRET_ACCESS_KEY}'
        export TF_VAR_region='${region}'

        # Remove manual AWS config file creation
        # Remove cluster existence check via AWS CLI
        
        # Use Terraform state for cluster existence check
        if terraform state list module.eks 2>/dev/null; then
            echo "Cluster exists in Terraform state"
            if [ "${forceRecreate}" = "true" ]; then
                echo "Destroying existing cluster..."
                terraform destroy -target=module.eks -auto-approve
                sleep 30
            else
                exit 0
            fi
        fi

        # Initialize and apply
        terraform init -reconfigure
        terraform apply -auto-approve
        
        # Configure kubectl using module outputs
        aws eks update-kubeconfig --name ${clusterName} \
            --region ${region} \
            --alias automated-eks
    """
}