#!/usr/bin/env groovy

def call() {
    echo "Deploying monitoring stack..."
    
    // Ensure we're connected to the right cluster
    sh """
        aws eks update-kubeconfig --name ${env.EKS_CLUSTER_NAME} --region ${env.AWS_DEFAULT_REGION}
    """
    
    // Deploy Prometheus and Grafana
    sh '''
        # Create monitoring namespace
        kubectl create namespace monitoring --dry-run=client -o yaml | kubectl apply -f -
        
        # Install Prometheus Stack with Grafana
        helm upgrade --install prometheus prometheus-community/kube-prometheus-stack \
            --namespace monitoring \
            --set grafana.adminPassword=admin \
            --set grafana.service.type=ClusterIP \
            --set grafana.resources.requests.cpu=100m \
            --set grafana.resources.requests.memory=128Mi \
            --set grafana.resources.limits.cpu=200m \
            --set grafana.resources.limits.memory=256Mi \
            --set prometheus.prometheusSpec.resources.requests.cpu=200m \
            --set prometheus.prometheusSpec.resources.requests.memory=256Mi \
            --set prometheus.prometheusSpec.resources.limits.cpu=500m \
            --set prometheus.prometheusSpec.resources.limits.memory=512Mi
        
        # Wait for Prometheus and Grafana to be ready
        kubectl wait --for=condition=available --timeout=300s deployment/prometheus-kube-prometheus-operator -n monitoring || true
        kubectl wait --for=condition=available --timeout=300s deployment/prometheus-grafana -n monitoring || true
        
        # Create Ingress for Grafana
        cat <<EOF | kubectl apply -f -
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: grafana-ingress
  namespace: monitoring
  annotations:
    cert-manager.io/cluster-issuer: "letsencrypt-prod"
    kubernetes.io/ingress.class: "nginx"
    nginx.ingress.kubernetes.io/ssl-redirect: "true"
spec:
  tls:
  - hosts:
    - grafana.${DOMAIN_NAME}
    secretName: grafana-tls
  rules:
  - host: grafana.${DOMAIN_NAME}
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
    kubernetes.io/ingress.class: "nginx"
    nginx.ingress.kubernetes.io/ssl-redirect: "true"
spec:
  tls:
  - hosts:
    - prometheus.${DOMAIN_NAME}
    secretName: prometheus-tls
  rules:
  - host: prometheus.${DOMAIN_NAME}
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
    '''
    
    echo "Monitoring stack deployed successfully!"
}