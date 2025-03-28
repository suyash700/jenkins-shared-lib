#!/usr/bin/env groovy

def call() {
    echo "Deploying EasyShop application"
    
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
        
        // Install EBS CSI Driver for storage
        sh """
            chmod +x scripts/install-ebs-csi-driver.sh
            ./scripts/install-ebs-csi-driver.sh
        """
        
        // Create namespace if it doesn't exist
        sh '''
            kubectl create namespace easyshop --dry-run=client -o yaml | kubectl apply -f -
        '''
        
        // Apply Kubernetes manifests and check resources
        sh """
            chmod +x scripts/check-k8s-resources.sh
            ./scripts/check-k8s-resources.sh
        """
        
        // Get application URL
        sh '''
            echo "Application URLs:"
            echo "EasyShop Service: $(kubectl get svc easyshop-service -n easyshop -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || echo "Not available yet")"
            echo "Ingress URL: http://$(kubectl get ingress easyshop-ingress -n easyshop -o jsonpath='{.spec.rules[0].host}' 2>/dev/null || echo "Not available yet")"
        '''
    }
}