#!/usr/bin/env groovy

def call() {
    echo "Starting infrastructure provisioning..."
    
    // Set AWS region
    env.AWS_DEFAULT_REGION = env.AWS_DEFAULT_REGION ?: 'eu-north-1'
    
    // Setup Terraform backend
    sh '''
        chmod +x scripts/setup-terraform-backend.sh
        ./scripts/setup-terraform-backend.sh
    '''
    
    // Check if EKS cluster exists and decide whether to use Terraform or eksctl
    def clusterExists = sh(
        script: 'aws eks describe-cluster --name ${EKS_CLUSTER_NAME} --region ${AWS_DEFAULT_REGION} 2>/dev/null && echo "true" || echo "false"',
        returnStdout: true
    ).trim()
    
    if (clusterExists == 'true') {
        echo "EKS cluster ${env.EKS_CLUSTER_NAME} already exists"
        
        // Update kubeconfig
        sh '''
            aws eks update-kubeconfig --name ${EKS_CLUSTER_NAME} --region ${AWS_DEFAULT_REGION}
            kubectl cluster-info
        '''
    } else {
        echo "EKS cluster ${env.EKS_CLUSTER_NAME} does not exist, provisioning with Terraform"
        
        dir('terraform') {
            // Initialize Terraform
            sh 'terraform init -upgrade'
            
            // Plan and apply with improved error handling
            sh '''
                # Create a plan file
                terraform plan -var="environment=prod" -var="aws_region=${AWS_DEFAULT_REGION}" -out=tfplan
                
                # Apply the plan
                terraform apply -auto-approve tfplan
                
                # Extract outputs
                terraform output -json > terraform_outputs.json
            '''
            
            // Get EKS cluster name from Terraform output
            def eksClusterName = sh(
                script: 'jq -r .cluster_name.value terraform_outputs.json || echo "${EKS_CLUSTER_NAME}"',
                returnStdout: true
            ).trim()
            
            env.EKS_CLUSTER_NAME = eksClusterName
            echo "EKS Cluster Name: ${env.EKS_CLUSTER_NAME}"
            
            // Update kubeconfig
            sh '''
                aws eks update-kubeconfig --name ${EKS_CLUSTER_NAME} --region ${AWS_DEFAULT_REGION}
                kubectl cluster-info
            '''
        }
    }
    
    // Create a script to fix any potential issues with the EKS cluster
    sh '''
        cat > scripts/fix-eks-issues.sh << 'EOF'
#!/bin/bash
set -e

echo "Checking and fixing EKS cluster issues..."

# Ensure all required namespaces exist
for ns in argocd ingress-nginx cert-manager monitoring easyshop; do
  kubectl create namespace $ns --dry-run=client -o yaml | kubectl apply -f -
done

# Check if NGINX Ingress Controller is installed
if ! kubectl get deployment -n ingress-nginx ingress-nginx-controller &>/dev/null; then
  echo "Installing NGINX Ingress Controller..."
  helm upgrade --install ingress-nginx ingress-nginx/ingress-nginx \
    --namespace ingress-nginx \
    --set controller.service.type=LoadBalancer \
    --set controller.metrics.enabled=true
fi

# Check if cert-manager is installed
if ! kubectl get deployment -n cert-manager cert-manager &>/dev/null; then
  echo "Installing cert-manager..."
  helm upgrade --install cert-manager jetstack/cert-manager \
    --namespace cert-manager \
    --set installCRDs=true
fi

# Create ClusterIssuer for Let's Encrypt
cat <<EOL | kubectl apply -f -
apiVersion: cert-manager.io/v1
kind: ClusterIssuer
metadata:
  name: letsencrypt-prod
spec:
  acme:
    server: https://acme-v02.api.letsencrypt.org/directory
    email: admin@${DOMAIN_NAME}
    privateKeySecretRef:
      name: letsencrypt-prod
    solvers:
    - http01:
        ingress:
          class: nginx
EOL

echo "EKS cluster setup completed!"
EOF

        chmod +x scripts/fix-eks-issues.sh
        ./scripts/fix-eks-issues.sh
    '''
    
    echo "Infrastructure provisioning completed successfully!"
}