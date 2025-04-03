def call(Map config = [:]) {
    def scriptPath = config.scriptPath ?: 'scripts/terraform-bootstrap.sh'
    
    // Check if AWS credentials are available before proceeding
    if (env.AWS_ACCESS_KEY_ID == null || env.AWS_SECRET_ACCESS_KEY == null) {
        error "AWS credentials are required for Terraform backend setup. Please check Jenkins configuration."
    }
    
    sh """
        # Make bootstrap script executable
        chmod +x ${scriptPath}
        
        # Run the bootstrap script to create S3 bucket and DynamoDB table
        echo "Setting up Terraform backend infrastructure..."
        ./${scriptPath}
    """
}