#!/usr/bin/env groovy

def call() {
    echo "Deploying EasyShop application..."
    
    // Ensure we're connected to the right cluster
    sh """
        aws eks update-kubeconfig --name ${env.EKS_CLUSTER_NAME} --region ${env.AWS_DEFAULT_REGION}
    """
    
    // Install ArgoCD if not already installed
    sh '''
        # Check if ArgoCD is installed
        if ! kubectl get deployment argocd-server -n argocd &>/dev/null; then
            echo "Installing ArgoCD..."
            helm upgrade --install argocd argo/argo-cd \
                --namespace argocd \
                --set server.service.type=ClusterIP
        else
            echo "ArgoCD is already installed"
        fi
        
        # Wait for ArgoCD to be ready
        kubectl wait --for=condition=available --timeout=300s deployment/argocd-server -n argocd || true
        
        # Configure ArgoCD repository
        kubectl patch configmap argocd-cm -n argocd --type merge -p '{"data":{"repositories":"- url: https://github.com/iemafzalhassan/EasyShop-KIND.git\\n  type: git\\n"}}' || true
        
        # Restart ArgoCD components to apply changes
        kubectl rollout restart deployment argocd-repo-server -n argocd
        kubectl rollout restart deployment argocd-server -n argocd
    '''
    
    // Deploy EasyShop application
    sh '''
        # Create easyshop namespace
        kubectl create namespace easyshop --dry-run=client -o yaml | kubectl apply -f -
        
        # Create ConfigMap for application configuration
        cat <<EOF | kubectl apply -f -
apiVersion: v1
kind: ConfigMap
metadata:
  name: easyshop-config
  namespace: easyshop
data:
  MONGODB_URI: "mongodb://mongodb-service.easyshop.svc.cluster.local:27017/easyshop"
  REDIS_URI: "redis://redis.easyshop.svc.cluster.local:6379"
  NODE_ENV: "production"
  NEXT_PUBLIC_API_URL: "https://easyshop.${DOMAIN_NAME}/api"
  NEXTAUTH_URL: "https://easyshop.${DOMAIN_NAME}"
  NEXTAUTH_SECRET: "HmaFjYZ2jbUK7Ef+wZrBiJei4ZNGBAJ5IdiOGAyQegw="
  JWT_SECRET: "e5e425764a34a2117ec2028bd53d6f1388e7b90aeae9fa7735f2469ea3a6cc8c"
EOF
        
        # Apply all Kubernetes manifests
        for file in kubernetes/*.yaml; do
            if [ -f "$file" ] && [ "$(basename $file)" != "00-kind-config.yaml" ]; then
                echo "Applying $file"
                kubectl apply -f $file || true
            fi
        done
        
        # Create Ingress for EasyShop
        cat <<EOF | kubectl apply -f -
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: easyshop-ingress
  namespace: easyshop
  annotations:
    cert-manager.io/cluster-issuer: "letsencrypt-prod"
    kubernetes.io/ingress.class: "nginx"
    nginx.ingress.kubernetes.io/ssl-redirect: "true"
    nginx.ingress.kubernetes.io/proxy-body-size: "50m"
spec:
  tls:
  - hosts:
    - easyshop.${DOMAIN_NAME}
    secretName: easyshop-tls
  rules:
  - host: easyshop.${DOMAIN_NAME}
    http:
      paths:
      - path: /
        pathType: Prefix
        backend:
          service:
            name: easyshop-service
            port:
              number: 80
EOF
        
        # Create Ingress for ArgoCD
        cat <<EOF | kubectl apply -f -
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: argocd-ingress
  namespace: argocd
  annotations:
    cert-manager.io/cluster-issuer: "letsencrypt-prod"
    kubernetes.io/ingress.class: "nginx"
    nginx.ingress.kubernetes.io/ssl-redirect: "true"
    nginx.ingress.kubernetes.io/backend-protocol: "HTTPS"
    nginx.ingress.kubernetes.io/ssl-passthrough: "true"
spec:
  tls:
  - hosts:
    - argocd.${DOMAIN_NAME}
    secretName: argocd-tls
  rules:
  - host: argocd.${DOMAIN_NAME}
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
        
        # Run database migrations
        echo "Running database migrations..."
        kubectl apply -f kubernetes/12-migration-job.yaml || true
        
        # Wait for EasyShop deployment to be ready
        echo "Waiting for EasyShop deployment to be ready..."
        kubectl wait --for=condition=available --timeout=300s deployment/easyshop -n easyshop || true
    '''
    
    echo "EasyShop application deployed successfully!"
}