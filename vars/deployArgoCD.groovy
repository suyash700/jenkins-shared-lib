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
            
            # Create Ingress for ArgoCD
            cat <<EOF | kubectl apply -f -
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: argocd-ingress
  namespace: argocd
  annotations:
    cert-manager.io/cluster-issuer: "letsencrypt-prod"
    nginx.ingress.kubernetes.io/backend-protocol: "HTTPS"
    nginx.ingress.kubernetes.io/ssl-passthrough: "true"
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
            
            # Get ArgoCD initial admin password
            ARGOCD_PASSWORD=$(kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath="{.data.password}" | base64 -d)
            echo "ArgoCD Initial Admin Password: $ARGOCD_PASSWORD"
            echo "ArgoCD URL: https://argocd.iemafzalhassan.tech"
            echo "Username: admin"
            
            # Apply ArgoCD application
            kubectl apply -f kubernetes/argocd/application.yaml
        '''
    }
}