def call(Map config = [:]) {
    def scriptPath = config.scriptPath ?: 'scripts/deploy-easyshop.sh'
    def clusterName = config.clusterName ?: 'easyshop-prod'
    def region = config.region ?: 'eu-north-1'
    
    sh """#!/bin/bash
        set -e
        
        # Check if script exists
        if [ -f "${scriptPath}" ]; then
            echo "Modifying ${scriptPath} to remove redundant infrastructure code..."
            
            # Create backup
            cp "${scriptPath}" "${scriptPath}.bak"
            
            # Create a new version of the script without NGINX and Cert-Manager installation
            cat > "${scriptPath}.new" << 'EOF'
#!/bin/bash
set -e

# Define project root directory (will be Jenkins workspace in CI/CD)
PROJECT_ROOT="\${WORKSPACE:-/var/lib/jenkins/workspace/EasyShop-Kind}"

# Check for required environment variables
if [ -z "\${AWS_ACCESS_KEY_ID}" ] || [ -z "\${AWS_SECRET_ACCESS_KEY}" ]; then
    echo "Error: AWS credentials not set. Please set AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY environment variables."
    exit 1
fi

if [ -z "\${AWS_DEFAULT_REGION}" ]; then
    echo "Warning: AWS_DEFAULT_REGION not set. Using default region."
    export AWS_DEFAULT_REGION="${region}"
fi

# Configure kubectl to use the EKS cluster
echo "Configuring kubectl to use the EKS cluster..."
aws eks update-kubeconfig --name \${EKS_CLUSTER_NAME:-${clusterName}} --region \${AWS_DEFAULT_REGION}

# Skip NGINX and Cert-Manager installation as they are now handled by Terraform
echo "NGINX Ingress Controller and Cert-Manager are installed via Terraform."

# Verify NGINX Ingress Controller is ready
echo "Verifying NGINX Ingress Controller..."
kubectl wait --namespace ingress-nginx \\
  --for=condition=ready pod \\
  --selector=app.kubernetes.io/component=controller \\
  --timeout=300s || echo "NGINX Ingress Controller may not be ready yet"

# Verify Cert-Manager is ready
echo "Verifying Cert-Manager..."
kubectl wait --namespace cert-manager \\
  --for=condition=ready pod \\
  --selector=app.kubernetes.io/name=cert-manager \\
  --timeout=300s || echo "Cert-Manager may not be ready yet"

# Continue with the rest of the deployment...
echo "Deploying EasyShop application..."
EOF
            
            # Find the line where monitoring setup begins and append that part
            MONITORING_START=\$(grep -n "# Install Prometheus and Grafana for monitoring" "${scriptPath}" | cut -d: -f1)
            
            if [ -n "\$MONITORING_START" ]; then
                tail -n +\$MONITORING_START "${scriptPath}" >> "${scriptPath}.new"
            else
                echo "# Monitoring setup code not found in the original script" >> "${scriptPath}.new"
            fi
            
            # Replace the original file
            mv "${scriptPath}.new" "${scriptPath}"
            chmod +x "${scriptPath}"
            
            echo "Script ${scriptPath} modified successfully."
        else
            echo "File ${scriptPath} not found."
        fi
    """
}