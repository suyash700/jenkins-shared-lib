#!/usr/bin/env groovy

def call() {
    echo "Installing required dependencies..."
    
    sh '''
        # Create bin directory if it doesn't exist
        mkdir -p $HOME/bin
        export PATH=$HOME/bin:$PATH
        
        # Install AWS CLI
        if ! command -v aws &> /dev/null; then
            echo "Installing AWS CLI..."
            curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "awscliv2.zip"
            unzip -q awscliv2.zip
            ./aws/install -i $HOME/aws-cli -b $HOME/bin
            rm -rf aws awscliv2.zip
        else
            echo "AWS CLI already installed: $(aws --version)"
        fi
        
        # Install kubectl
        if ! command -v kubectl &> /dev/null; then
            echo "Installing kubectl..."
            curl -LO "https://dl.k8s.io/release/$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl"
            chmod +x kubectl
            mv kubectl $HOME/bin/
        else
            echo "kubectl already installed: $(kubectl version --client)"
        fi
        
        # Install eksctl
        if ! command -v eksctl &> /dev/null; then
            echo "Installing eksctl..."
            curl --silent --location "https://github.com/weaveworks/eksctl/releases/latest/download/eksctl_$(uname -s)_amd64.tar.gz" | tar xz -C /tmp
            mv /tmp/eksctl $HOME/bin/
            chmod +x $HOME/bin/eksctl
        else
            echo "eksctl already installed: $(eksctl version)"
        fi
        
        # Install Helm
        if ! command -v helm &> /dev/null; then
            echo "Installing Helm..."
            curl -fsSL -o get_helm.sh https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3
            chmod +x get_helm.sh
            ./get_helm.sh
            rm get_helm.sh
        else
            echo "Helm already installed: $(helm version --short)"
        fi
        
        # Install Trivy
        if ! command -v trivy &> /dev/null; then
            echo "Installing Trivy..."
            curl -sfL https://raw.githubusercontent.com/aquasecurity/trivy/main/contrib/install.sh | sh -s -- -b $HOME/bin
        else
            echo "Trivy already installed: $(trivy --version)"
        fi
        
        # Add Helm repositories
        echo "Adding Helm repositories..."
        helm repo add ingress-nginx https://kubernetes.github.io/ingress-nginx
        helm repo add jetstack https://charts.jetstack.io
        helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
        helm repo add grafana https://grafana.github.io/helm-charts
        helm repo add argo https://argoproj.github.io/argo-helm
        helm repo update
        
        echo "All dependencies installed successfully!"
    '''
}

def call() {
    echo "Installing dependencies for EasyShop application"
    
    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', 
                      credentialsId: 'aws-access-key', 
                      accessKeyVariable: 'AWS_ACCESS_KEY_ID', 
                      secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        
        // Set AWS region
        env.AWS_DEFAULT_REGION = 'eu-north-1'
        
        // Configure kubectl to use the EKS cluster
        sh """
            aws eks update-kubeconfig --name ${env.EKS_CLUSTER_NAME} --region eu-north-1
            kubectl config current-context
        """
        
        // Install NGINX Ingress Controller
        sh '''
            helm repo add ingress-nginx https://kubernetes.github.io/ingress-nginx
            helm repo update
            
            helm upgrade --install ingress-nginx ingress-nginx/ingress-nginx \
                --namespace ingress-nginx \
                --create-namespace \
                --set controller.service.type=NodePort \
                --set controller.service.nodePorts.http=30080 \
                --set controller.service.nodePorts.https=30443
                
            # Wait for NGINX Ingress to be ready
            kubectl wait --for=condition=available --timeout=300s deployment/ingress-nginx-controller -n ingress-nginx
        '''
        
        // Install cert-manager for SSL certificates
        sh '''
            helm repo add jetstack https://charts.jetstack.io
            helm repo update
            
            helm upgrade --install cert-manager jetstack/cert-manager \
                --namespace cert-manager \
                --create-namespace \
                --set installCRDs=true
                
            # Wait for cert-manager to be ready
            kubectl wait --for=condition=available --timeout=300s deployment/cert-manager -n cert-manager
            
            # Create ClusterIssuer for Let's Encrypt
            cat <<EOF | kubectl apply -f -
apiVersion: cert-manager.io/v1
kind: ClusterIssuer
metadata:
  name: letsencrypt-prod
spec:
  acme:
    server: https://acme-v02.api.letsencrypt.org/directory
    email: admin@iemafzalhassan.tech
    privateKeySecretRef:
      name: letsencrypt-prod
    solvers:
    - http01:
        ingress:
          class: nginx
EOF
        '''
        
        // Create easyshop namespace
        sh '''
            kubectl create namespace easyshop --dry-run=client -o yaml | kubectl apply -f -
        '''
    }
}