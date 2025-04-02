#!/usr/bin/env groovy

def call() {
    echo "Verifying deployment..."
    
    // Ensure we're connected to the right cluster
    sh """
        aws eks update-kubeconfig --name ${env.EKS_CLUSTER_NAME} --region ${env.AWS_DEFAULT_REGION}
    """
    
    // Create a script to check deployment status and generate access information
    sh '''
        cat > scripts/generate-report.sh << 'EOF'
#!/bin/bash
set -e

echo "===== EasyShop Deployment Report ====="
echo "Generated: $(date)"
echo ""

# Get cluster info
echo "===== Cluster Information ====="
kubectl cluster-info
echo ""

# Get nodes
echo "===== Cluster Nodes ====="
kubectl get nodes
echo ""

# Get namespaces
echo "===== Namespaces ====="
kubectl get namespaces
echo ""

# Get all resources in easyshop namespace
echo "===== EasyShop Resources ====="
kubectl get all -n easyshop
echo ""

# Get all resources in argocd namespace
echo "===== ArgoCD Resources ====="
kubectl get all -n argocd
echo ""

# Get all resources in monitoring namespace
echo "===== Monitoring Resources ====="
kubectl get all -n monitoring
echo ""

# Get all ingresses
echo "===== Ingress Resources ====="
kubectl get ingress --all-namespaces
echo ""

# Get LoadBalancer information
echo "===== LoadBalancer Information ====="
NGINX_LB=$(kubectl get svc ingress-nginx-controller -n ingress-nginx -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || echo "Not available yet")
echo "NGINX Ingress LoadBalancer: $NGINX_LB"
echo ""

# Get ArgoCD password
echo "===== ArgoCD Credentials ====="
echo "Username: admin"
ARGOCD_PASSWORD=$(kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath="{.data.password}" 2>/dev/null | base64 -d || echo "admin123")
echo "Password: $ARGOCD_PASSWORD"
echo ""

# Get Grafana credentials
echo "===== Grafana Credentials ====="
echo "Username: admin"
echo "Password: admin"
echo ""

# Access Information
echo "===== Access Information ====="
echo "To access your applications, create the following DNS records:"
echo "1. Create a CNAME record for easyshop.${DOMAIN_NAME} pointing to $NGINX_LB"
echo "2. Create a CNAME record for argocd.${DOMAIN_NAME} pointing to $NGINX_LB"
echo "3. Create a CNAME record for grafana.${DOMAIN_NAME} pointing to $NGINX_LB"
echo "4. Create a CNAME record for prometheus.${DOMAIN_NAME} pointing to $NGINX_LB"
echo ""

echo "Your applications will be accessible at:"
echo "- EasyShop: https://easyshop.${DOMAIN_NAME}"
echo "- ArgoCD: https://argocd.${DOMAIN_NAME}"
echo "- Grafana: https://grafana.${DOMAIN_NAME}"
echo "- Prometheus: https://prometheus.${DOMAIN_NAME}"
echo ""

# Check for any issues
echo "===== Potential Issues ====="
kubectl get pods --all-namespaces | grep -v "Running\\|Completed" || echo "No issues found!"
echo ""

echo "===== End of Report ====="
EOF

        chmod +x scripts/generate-report.sh
        ./scripts/generate-report.sh
    '''
    
    echo "Deployment verification completed!"
}