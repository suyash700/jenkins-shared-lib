#!/usr/bin/env groovy

def call() {
    echo "Deploying Prometheus and Grafana for monitoring"
    
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
        
        // Deploy Prometheus and Grafana
        sh '''
            # Add Helm repositories
            helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
            helm repo update
            
            # Create monitoring namespace
            kubectl create namespace monitoring --dry-run=client -o yaml | kubectl apply -f -
            
            # Install Prometheus Stack with Grafana
            helm upgrade --install prometheus prometheus-community/kube-prometheus-stack \
                --namespace monitoring \
                --set grafana.adminPassword=admin \
                --set grafana.service.type=LoadBalancer \
                --wait \
                --timeout 15m
            
            # Wait for Grafana to be ready
            echo "Waiting for Grafana to be ready..."
            kubectl wait --for=condition=available --timeout=300s deployment/prometheus-grafana -n monitoring || true
            
            # Wait for ingress controller to be fully ready
            echo "Ensuring ingress controller is fully ready..."
            kubectl wait --for=condition=available --timeout=300s deployment/ingress-nginx-controller -n ingress-nginx || true
            
            # Give the admission webhook some time to be fully operational
            echo "Waiting for admission webhook to be ready..."
            sleep 60
            
            # Create Ingress for Grafana with retry logic
            echo "Creating Grafana ingress with retry logic..."
            for i in {1..5}; do
                echo "Attempt $i to create Grafana ingress..."
                cat <<EOF | kubectl apply -f - && break || sleep 30
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: grafana-ingress
  namespace: monitoring
  annotations:
    cert-manager.io/cluster-issuer: "letsencrypt-prod"
    nginx.ingress.kubernetes.io/backend-protocol: "HTTP"
    nginx.ingress.kubernetes.io/timeout-seconds: "60"
spec:
  ingressClassName: nginx
  tls:
  - hosts:
    - grafana.iemafzalhassan.tech
    secretName: grafana-tls
  rules:
  - host: grafana.iemafzalhassan.tech
    http:
      paths:
      - path: /
        pathType: Prefix
        backend:
          service:
            name: prometheus-grafana
            port:
              number: 80
EOF
            done
            
            # Create Ingress for Prometheus with retry logic
            echo "Creating Prometheus ingress with retry logic..."
            for i in {1..5}; do
                echo "Attempt $i to create Prometheus ingress..."
                cat <<EOF | kubectl apply -f - && break || sleep 30
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: prometheus-ingress
  namespace: monitoring
  annotations:
    cert-manager.io/cluster-issuer: "letsencrypt-prod"
    nginx.ingress.kubernetes.io/backend-protocol: "HTTP"
    nginx.ingress.kubernetes.io/timeout-seconds: "60"
spec:
  ingressClassName: nginx
  tls:
  - hosts:
    - prometheus.iemafzalhassan.tech
    secretName: prometheus-tls
  rules:
  - host: prometheus.iemafzalhassan.tech
    http:
      paths:
      - path: /
        pathType: Prefix
        backend:
          service:
            name: prometheus-kube-prometheus-prometheus
            port:
              number: 9090
EOF
            done
            
            # Get Grafana LoadBalancer URL
            echo "Grafana URL: https://grafana.iemafzalhassan.tech"
            echo "Prometheus URL: https://prometheus.iemafzalhassan.tech"
            echo "Grafana Username: admin"
            echo "Grafana Password: admin"
            
            # Display the actual LoadBalancer URL as well
            GRAFANA_LB=$(kubectl get svc prometheus-grafana -n monitoring -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || echo "Not available yet")
            echo "Grafana LoadBalancer URL: http://$GRAFANA_LB"
        '''
    }
}