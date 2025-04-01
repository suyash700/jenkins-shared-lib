#!/usr/bin/env groovy

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