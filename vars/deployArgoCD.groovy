#!/usr/bin/env groovy

def call() {
    echo "Deploying ArgoCD"
    
    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', 
                      credentialsId: 'aws-access-key', 
                      accessKeyVariable: 'AWS_ACCESS_KEY_ID', 
                      secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        
        // Set AWS region
        env.AWS_DEFAULT_REGION = 'eu-north-1'
        
        // Update kubeconfig with EKS cluster info
        sh """
            aws eks update-kubeconfig --name ${env.EKS_CLUSTER_NAME} --region eu-north-1
            kubectl config current-context
        """
        
        // Deploy ArgoCD using Helm
        sh '''
            # Add Helm repo
            helm repo add argo https://argoproj.github.io/argo-helm
            helm repo update
            
            # Create ArgoCD namespace
            kubectl create namespace argocd --dry-run=client -o yaml | kubectl apply -f -
            
            # Install ArgoCD
            helm upgrade --install argocd argo/argo-cd \
                --namespace argocd \
                --set server.service.type=NodePort \
                --set server.service.nodePortHttp=30080 \
                --set server.service.nodePortHttps=30443 \
                --wait
                
            # Wait for ArgoCD to be ready
            kubectl wait --for=condition=available --timeout=300s deployment/argocd-server -n argocd
            
            # Wait for ingress controller to be fully ready
            echo "Ensuring ingress controller is fully ready..."
            kubectl wait --for=condition=available --timeout=300s deployment/ingress-nginx-controller -n ingress-nginx || true
            
            # Give the admission webhook some time to be fully operational
            echo "Waiting for admission webhook to be ready..."
            sleep 60
            
            # Create Ingress for ArgoCD with retry logic
            echo "Creating ArgoCD ingress with retry logic..."
            for i in {1..5}; do
                echo "Attempt $i to create ArgoCD ingress..."
                cat <<EOF | kubectl apply -f - && break || sleep 30
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: argocd-ingress
  namespace: argocd
  annotations:
    cert-manager.io/cluster-issuer: "letsencrypt-prod"
    nginx.ingress.kubernetes.io/backend-protocol: "HTTPS"
    nginx.ingress.kubernetes.io/ssl-passthrough: "true"
    nginx.ingress.kubernetes.io/timeout-seconds: "60"
spec:
  ingressClassName: nginx
  tls:
  - hosts:
    - argocd.iemafzalhassan.tech
    secretName: argocd-tls
  rules:
  - host: argocd.iemafzalhassan.tech
    http:
      paths:
      - path: /
        pathType: Prefix
        backend:
          service:
            name: argocd-server
            port:
              number: 443
EOF
            done
            
            # Get ArgoCD initial admin password
            ARGOCD_PASSWORD=$(kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath="{.data.password}" | base64 -d)
            echo "ArgoCD Initial Admin Password: $ARGOCD_PASSWORD"
            echo "ArgoCD URL: https://argocd.iemafzalhassan.tech"
            echo "Username: admin"
            
            # Get the NGINX Ingress Controller LoadBalancer URL
            NGINX_LB=$(kubectl get svc ingress-nginx-controller -n ingress-nginx -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || echo "Not available yet")
            echo "NGINX Ingress LoadBalancer: $NGINX_LB"
            
            # Apply ArgoCD application if it exists
            if [ -f "kubernetes/argocd/application.yaml" ]; then
                echo "Applying ArgoCD application..."
                kubectl apply -f kubernetes/argocd/application.yaml || echo "Failed to apply ArgoCD application, but continuing"
            else
                echo "No ArgoCD application manifest found, skipping"
            fi
        '''
    }
}