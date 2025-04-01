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
            # Apply all Kubernetes manifests in order
            kubectl apply -f kubernetes/01-namespace.yaml || true
            kubectl apply -f kubernetes/02-storage.yaml || true
            kubectl apply -f kubernetes/03-secrets.yaml || true
            kubectl apply -f kubernetes/04-configmap.yaml || true
            kubectl apply -f kubernetes/05-mongodb.yaml || true
            kubectl apply -f kubernetes/06-redis.yaml || true
            kubectl apply -f kubernetes/07-service.yaml || true
            kubectl apply -f kubernetes/08-easyshop-deployment.yaml || true
            kubectl apply -f kubernetes/09-hpa.yaml || true
            
            # Wait for MongoDB and Redis to be ready
            echo "Waiting for MongoDB to be ready..."
            kubectl wait --for=condition=ready pod -l app=mongodb -n easyshop --timeout=300s || true
            
            echo "Waiting for Redis to be ready..."
            kubectl wait --for=condition=ready pod -l app=redis -n easyshop --timeout=300s || true
            
            # Wait for EasyShop deployment to be ready
            echo "Waiting for EasyShop deployment to be ready..."
            kubectl wait --for=condition=available deployment/easyshop -n easyshop --timeout=300s || true
            
            # Wait for ingress controller to be fully ready
            echo "Ensuring ingress controller is fully ready..."
            kubectl wait --for=condition=available deployment/ingress-nginx-controller -n ingress-nginx --timeout=300s || true
            
            # Give the admission webhook some time to be fully operational
            echo "Waiting for admission webhook to be ready..."
            sleep 60
            
            # Apply ingress with retry logic
            echo "Creating EasyShop ingress with retry logic..."
            for i in {1..5}; do
                echo "Attempt $i to create EasyShop ingress..."
                kubectl apply -f kubernetes/10-ingress.yaml && break || sleep 30
            done
            
            # Run database migrations if needed
            echo "Running database migrations..."
            kubectl apply -f kubernetes/12-migration-job.yaml || true
            
            # Check if services are exposed properly
            echo "Checking service exposure..."
            kubectl get svc -n easyshop
            kubectl get ingress -n easyshop
            
            # Check if LoadBalancer has an external IP/hostname
            echo "Checking LoadBalancer status..."
            kubectl get svc -n ingress-nginx
        """
        
        // Get application URL and verify access
        sh '''
            echo "Application URLs:"
            
            # Get the EasyShop service URL
            EASYSHOP_SVC=$(kubectl get svc easyshop-service -n easyshop -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || echo "Not available yet")
            echo "EasyShop Service: $EASYSHOP_SVC"
            
            # Get the Ingress URL
            INGRESS_HOST=$(kubectl get ingress easyshop-ingress -n easyshop -o jsonpath='{.spec.rules[0].host}' 2>/dev/null || echo "Not available yet")
            echo "Ingress URL: https://$INGRESS_HOST"
            
            # Get the NGINX Ingress Controller LoadBalancer URL
            NGINX_LB=$(kubectl get svc ingress-nginx-controller -n ingress-nginx -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || echo "Not available yet")
            echo "NGINX Ingress LoadBalancer: $NGINX_LB"
            
            # Verify DNS resolution for the ingress host
            if [ "$INGRESS_HOST" != "Not available yet" ]; then
                echo "Checking DNS resolution for $INGRESS_HOST..."
                nslookup $INGRESS_HOST || echo "DNS resolution failed - you may need to add a DNS record pointing to the NGINX LoadBalancer"
            fi
            
            # Provide instructions for DNS setup if needed
            if [ "$NGINX_LB" != "Not available yet" ] && [ "$INGRESS_HOST" != "Not available yet" ]; then
                echo ""
                echo "===== IMPORTANT DNS SETUP INSTRUCTIONS ====="
                echo "To access your application, create the following DNS records:"
                echo "1. Create a CNAME record for $INGRESS_HOST pointing to $NGINX_LB"
                echo "2. Create a CNAME record for argocd.iemafzalhassan.tech pointing to $NGINX_LB"
                echo "3. Create a CNAME record for grafana.iemafzalhassan.tech pointing to $NGINX_LB"
                echo "4. Create a CNAME record for prometheus.iemafzalhassan.tech pointing to $NGINX_LB"
                echo "============================================"
            fi
        '''
    }
}