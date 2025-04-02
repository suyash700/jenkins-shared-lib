#!/usr/bin/env groovy

def call() {
    echo "Configuring environment for EasyShop application"
    
    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', 
                      credentialsId: 'aws-access-key', 
                      accessKeyVariable: 'AWS_ACCESS_KEY_ID', 
                      secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        
        // Set AWS region
        env.AWS_DEFAULT_REGION = 'eu-north-1'
        
        // Ensure we're connected to the right cluster
        sh """
            aws eks update-kubeconfig --name ${env.EKS_CLUSTER_NAME} --region eu-north-1
        """
        
        // Deploy Prometheus and Grafana for monitoring
        sh '''
            # Add Helm repositories
            helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
            helm repo add grafana https://grafana.github.io/helm-charts
            helm repo update
            
            # Create monitoring namespace
            kubectl create namespace monitoring --dry-run=client -o yaml | kubectl apply -f -
            
            # Install Prometheus Stack with Grafana
            helm upgrade --install prometheus prometheus-community/kube-prometheus-stack \
                --namespace monitoring \
                --set grafana.adminPassword=admin \
                --set grafana.service.type=LoadBalancer \
                --wait \
                --timeout 10m
                
            # Get Grafana URL
            echo "Waiting for Grafana LoadBalancer..."
            sleep 30
            GRAFANA_URL=$(kubectl get svc -n monitoring prometheus-grafana -o jsonpath='{.status.loadBalancer.ingress[0].hostname}')
            echo "Grafana is available at: http://$GRAFANA_URL"
            echo "Username: admin"
            echo "Password: admin"
            
            # Uninstall Loki first if it exists
            echo "Checking if Loki exists..."
            if helm ls -n monitoring | grep -q "^loki[[:space:]]"; then
                echo "Uninstalling existing Loki installation..."
                helm uninstall loki -n monitoring
                # Wait for resources to be deleted
                sleep 30
            else
                echo "Loki is not installed, proceeding with installation..."
            fi
            
            # Install Loki with smaller resource requirements
            echo "Installing Loki for log aggregation..."
            helm install loki grafana/loki-stack \
                --namespace monitoring \
                --set promtail.enabled=true \
                --set loki.persistence.enabled=true \
                --set loki.persistence.size=5Gi \
                --set loki.resources.requests.cpu=100m \
                --set loki.resources.requests.memory=256Mi \
                --set loki.resources.limits.cpu=200m \
                --set loki.resources.limits.memory=512Mi \
                --timeout 15m || echo "Loki installation timed out, but continuing pipeline"
        '''
    }
}