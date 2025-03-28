#!/usr/bin/env groovy

def call() {
    echo "Deploying ArgoCD"
    
    // First, ensure Helm is installed
    installHelm()
    
    // Configure kubectl to use the EKS cluster with proper AWS authentication
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
            kubectl cluster-info
        """
        
        // Deploy ArgoCD using Helm
        sh '''
            # Add Helm repo
            helm repo add argo https://argoproj.github.io/argo-helm
            helm repo update
            
            # Install ArgoCD
            helm upgrade --install argocd argo/argo-cd \
                --namespace argocd \
                --create-namespace \
                --version 5.46.7 \
                --set server.service.type=LoadBalancer \
                --wait
                
            # Wait for ArgoCD to be ready
            echo "Waiting for ArgoCD server to be ready..."
            kubectl wait --for=condition=available --timeout=300s deployment/argocd-server -n argocd || true
            
            # Apply ArgoCD application
            kubectl apply -f kubernetes/argocd/application.yaml
            
            # Get ArgoCD server URL
            echo "ArgoCD Server URL:"
            kubectl get svc argocd-server -n argocd -o jsonpath='{.status.loadBalancer.ingress[0].hostname}'
            
            # Get initial admin password
            echo "ArgoCD Initial Admin Password:"
            kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath="{.data.password}" | base64 -d
        '''
    }
}