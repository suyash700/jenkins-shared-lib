#!/usr/bin/env groovy

def call() {
    echo "Deploying EasyShop application with Helm"
    
    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', 
                      credentialsId: 'aws-access-key', 
                      accessKeyVariable: 'AWS_ACCESS_KEY_ID', 
                      secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        
        // Set AWS region
        env.AWS_DEFAULT_REGION = 'eu-north-1'
        
        // Ensure we're connected to the right cluster
        sh """
            aws eks update-kubeconfig --name ${env.EKS_CLUSTER_NAME} --region eu-north-1
            kubectl config current-context
        """
        
        // Create namespace if it doesn't exist
        sh '''
            kubectl create namespace easyshop --dry-run=client -o yaml | kubectl apply -f -
        '''
        
        // Apply Kubernetes manifests
        sh '''
            # Apply all Kubernetes manifests in order
            for file in $(ls -v kubernetes/*.yaml); do
                if [[ "$file" != "kubernetes/00-kind-config.yaml" ]]; then
                    echo "Applying $file"
                    kubectl apply -f $file
                fi
            done
            
            # Wait for MongoDB to be ready
            echo "Waiting for MongoDB to be ready..."
            kubectl wait --for=condition=ready pod -l app=mongodb -n easyshop --timeout=300s || true
            
            # Wait for Redis to be ready
            echo "Waiting for Redis to be ready..."
            kubectl wait --for=condition=ready pod -l app=redis -n easyshop --timeout=300s || true
            
            # Wait for EasyShop to be ready
            echo "Waiting for EasyShop to be ready..."
            kubectl wait --for=condition=available deployment/easyshop -n easyshop --timeout=300s || true
            
            # Get EasyShop service URL
            echo "EasyShop Service URL:"
            kubectl get svc easyshop-service -n easyshop -o jsonpath='{.status.loadBalancer.ingress[0].hostname}'
        '''
    }
}