#!/usr/bin/env groovy

def call() {
    echo "Deploying ArgoCD"
    
    sh '''
        # Add Helm repo
        helm repo add argo https://argoproj.github.io/argo-helm
        helm repo update
        
        # Install ArgoCD
        helm upgrade --install argocd argo/argo-cd \
            --namespace argocd \
            --create-namespace \
            --version 5.46.7 \
            --wait
    '''
    
    // Apply ArgoCD application
    sh 'kubectl apply -f kubernetes/argocd/application.yaml'
}