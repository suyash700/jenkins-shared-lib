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
                --set grafana.service.type=NodePort \
                --set grafana.service.nodePort=30300 \
                --wait
            
            # Create Ingress for Grafana
            cat <<EOF | kubectl apply -f -
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: grafana-ingress
  namespace: monitoring
  annotations:
    cert-manager.io/cluster-issuer: "letsencrypt-prod"
    nginx.ingress.kubernetes.io/backend-protocol: "HTTP"
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

            # Create Ingress for Prometheus
            cat <<EOF | kubectl apply -f -
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: prometheus-ingress
  namespace: monitoring
  annotations:
    cert-manager.io/cluster-issuer: "letsencrypt-prod"
    nginx.ingress.kubernetes.io/backend-protocol: "HTTP"
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
            
            echo "Grafana URL: https://grafana.iemafzalhassan.tech"
            echo "Prometheus URL: https://prometheus.iemafzalhassan.tech"
            echo "Grafana Username: admin"
            echo "Grafana Password: admin"
        '''
    }
}