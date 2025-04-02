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
            
            # Check if Prometheus is already installed
            if helm list -n monitoring | grep prometheus &>/dev/null; then
                echo "Prometheus is already installed, uninstalling it first..."
                helm uninstall prometheus -n monitoring
                
                # Wait for resources to be deleted
                echo "Waiting for Prometheus resources to be deleted..."
                kubectl wait --for=delete deployment/prometheus-kube-prometheus-operator -n monitoring --timeout=120s || true
                kubectl wait --for=delete deployment/prometheus-grafana -n monitoring --timeout=120s || true
                sleep 30
            fi
            
            # Install Prometheus Stack with Grafana
            echo "Installing Prometheus and Grafana..."
            helm upgrade --install prometheus prometheus-community/kube-prometheus-stack \
                --namespace monitoring \
                --set grafana.adminPassword=admin \
                --set grafana.service.type=LoadBalancer \
                --set grafana.service.port=80 \
                --set grafana.resources.requests.cpu=100m \
                --set grafana.resources.requests.memory=128Mi \
                --set grafana.resources.limits.cpu=200m \
                --set grafana.resources.limits.memory=256Mi \
                --set prometheus.prometheusSpec.resources.requests.cpu=200m \
                --set prometheus.prometheusSpec.resources.requests.memory=256Mi \
                --set prometheus.prometheusSpec.resources.limits.cpu=500m \
                --set prometheus.prometheusSpec.resources.limits.memory=512Mi \
                --set alertmanager.resources.requests.cpu=50m \
                --set alertmanager.resources.requests.memory=64Mi \
                --set alertmanager.resources.limits.cpu=100m \
                --set alertmanager.resources.limits.memory=128Mi \
                --wait \
                --timeout 15m
            
            # Wait for Grafana to be ready
            echo "Waiting for Grafana to be ready..."
            kubectl wait --for=condition=available --timeout=300s deployment/prometheus-grafana -n monitoring || true
            
            # Fix the NGINX Ingress Controller admission webhook timeout issue
            echo "Fixing NGINX Ingress Controller admission webhook timeout..."
            
            # Check if the ValidatingWebhookConfiguration exists
            if kubectl get validatingwebhookconfigurations ingress-nginx-admission &>/dev/null; then
                echo "Patching NGINX Ingress admission webhook timeout..."
                
                # Patch the webhook to increase the timeout
                kubectl patch validatingwebhookconfigurations ingress-nginx-admission \
                  --type='json' \
                  -p='[{"op": "replace", "path": "/webhooks/0/timeoutSeconds", "value": 30}]' || true
                
                # Restart the ingress controller to apply changes
                echo "Restarting NGINX Ingress Controller..."
                kubectl rollout restart deployment ingress-nginx-controller -n ingress-nginx
                
                # Wait for the ingress controller to be ready again
                echo "Waiting for NGINX Ingress Controller to be ready..."
                kubectl rollout status deployment ingress-nginx-controller -n ingress-nginx --timeout=300s || true
                
                # Additional wait to ensure webhook is fully operational
                echo "Additional wait for webhook to be fully operational..."
                sleep 90
            else
                echo "NGINX Ingress admission webhook not found, skipping patch"
            fi
            
            # Create Ingress for Grafana with improved retry logic
            echo "Creating Grafana ingress with improved retry logic..."
            for i in {1..10}; do
                echo "Attempt $i to create Grafana ingress..."
                if cat <<EOF | kubectl apply -f - ; then
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: grafana-ingress
  namespace: monitoring
  annotations:
    cert-manager.io/cluster-issuer: "letsencrypt-prod"
    nginx.ingress.kubernetes.io/backend-protocol: "HTTP"
    nginx.ingress.kubernetes.io/timeout-seconds: "60"
    nginx.ingress.kubernetes.io/proxy-body-size: "50m"
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
                    echo "Grafana ingress created successfully!"
                    break
                else
                    echo "Failed to create Grafana ingress, retrying in 30 seconds..."
                    sleep 30
                    
                    # If we're on the 5th attempt, try to fix the webhook again
                    if [ "$i" = "5" ]; then
                        echo "Still having issues, trying to fix webhook again..."
                        
                        # Try to restart the webhook
                        kubectl delete pod -l app.kubernetes.io/component=controller -n ingress-nginx || true
                        sleep 60
                        
                        # Try to patch the webhook again
                        kubectl patch validatingwebhookconfigurations ingress-nginx-admission \
                          --type='json' \
                          -p='[{"op": "replace", "path": "/webhooks/0/timeoutSeconds", "value": 30}]' || true
                        
                        # Additional wait
                        sleep 60
                    fi
                fi
            done
            
            # Create Ingress for Prometheus with improved retry logic
            echo "Creating Prometheus ingress with improved retry logic..."
            for i in {1..10}; do
                echo "Attempt $i to create Prometheus ingress..."
                if cat <<EOF | kubectl apply -f - ; then
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: prometheus-ingress
  namespace: monitoring
  annotations:
    cert-manager.io/cluster-issuer: "letsencrypt-prod"
    nginx.ingress.kubernetes.io/backend-protocol: "HTTP"
    nginx.ingress.kubernetes.io/timeout-seconds: "60"
    nginx.ingress.kubernetes.io/proxy-body-size: "50m"
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
                    echo "Prometheus ingress created successfully!"
                    break
                else
                    echo "Failed to create Prometheus ingress, retrying in 30 seconds..."
                    sleep 30
                fi
            done
            
            # Create Ingress for Alertmanager with improved retry logic
            echo "Creating Alertmanager ingress with improved retry logic..."
            for i in {1..10}; do
                echo "Attempt $i to create Alertmanager ingress..."
                if cat <<EOF | kubectl apply -f - ; then
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: alertmanager-ingress
  namespace: monitoring
  annotations:
    cert-manager.io/cluster-issuer: "letsencrypt-prod"
    nginx.ingress.kubernetes.io/backend-protocol: "HTTP"
    nginx.ingress.kubernetes.io/timeout-seconds: "60"
    nginx.ingress.kubernetes.io/proxy-body-size: "50m"
spec:
  ingressClassName: nginx
  tls:
  - hosts:
    - alertmanager.iemafzalhassan.tech
    secretName: alertmanager-tls
  rules:
  - host: alertmanager.iemafzalhassan.tech
    http:
      paths:
      - path: /
        pathType: Prefix
        backend:
          service:
            name: prometheus-kube-prometheus-alertmanager
            port:
              number: 9093
EOF
                    echo "Alertmanager ingress created successfully!"
                    break
                else
                    echo "Failed to create Alertmanager ingress, retrying in 30 seconds..."
                    sleep 30
                fi
            done
            
            # Install Loki for log aggregation
            echo "Installing Loki for log aggregation..."
            helm repo add grafana https://grafana.github.io/helm-charts
            helm repo update
            
            # Check if Loki is already installed
            if helm list -n monitoring | grep loki &>/dev/null; then
                echo "Loki is already installed, uninstalling it first..."
                helm uninstall loki -n monitoring
                sleep 30
            fi
            
            # Install Loki with smaller resource requirements and increased timeout
            echo "Installing Loki with increased timeout..."
            helm install loki grafana/loki-stack \
                --namespace monitoring \
                --set promtail.enabled=true \
                --set loki.persistence.enabled=true \
                --set loki.persistence.size=5Gi \
                --set loki.resources.requests.cpu=100m \
                --set loki.resources.requests.memory=256Mi \
                --set loki.resources.limits.cpu=200m \
                --set loki.resources.limits.memory=512Mi \
                --timeout 30m || echo "Loki installation timed out, but continuing pipeline"
            
            # Configure Grafana dashboards for EasyShop application
            echo "Configuring Grafana dashboards..."
            
            # Create ConfigMap for Node.js dashboard
            cat <<EOF | kubectl apply -f -
apiVersion: v1
kind: ConfigMap
metadata:
  name: nodejs-dashboard
  namespace: monitoring
  labels:
    grafana_dashboard: "1"
data:
  nodejs-dashboard.json: |
    {
      "annotations": {
        "list": [
          {
            "builtIn": 1,
            "datasource": "-- Grafana --",
            "enable": true,
            "hide": true,
            "iconColor": "rgba(0, 211, 255, 1)",
            "name": "Annotations & Alerts",
            "type": "dashboard"
          }
        ]
      },
      "editable": true,
      "gnetId": null,
      "graphTooltip": 0,
      "id": 1,
      "links": [],
      "panels": [
        {
          "aliasColors": {},
          "bars": false,
          "dashLength": 10,
          "dashes": false,
          "datasource": null,
          "fieldConfig": {
            "defaults": {
              "custom": {}
            },
            "overrides": []
          },
          "fill": 1,
          "fillGradient": 0,
          "gridPos": {
            "h": 8,
            "w": 12,
            "x": 0,
            "y": 0
          },
          "hiddenSeries": false,
          "id": 2,
          "legend": {
            "avg": false,
            "current": false,
            "max": false,
            "min": false,
            "show": true,
            "total": false,
            "values": false
          },
          "lines": true,
          "linewidth": 1,
          "nullPointMode": "null",
          "options": {
            "alertThreshold": true
          },
          "percentage": false,
          "pluginVersion": "7.2.0",
          "pointradius": 2,
          "points": false,
          "renderer": "flot",
          "seriesOverrides": [],
          "spaceLength": 10,
          "stack": false,
          "steppedLine": false,
          "targets": [
            {
              "expr": "sum(rate(container_cpu_usage_seconds_total{namespace=\\"easyshop\\", container!=\\"\\"}[5m])) by (container)",
              "interval": "",
              "legendFormat": "{{container}}",
              "refId": "A"
            }
          ],
          "thresholds": [],
          "timeFrom": null,
          "timeRegions": [],
          "timeShift": null,
          "title": "CPU Usage",
          "tooltip": {
            "shared": true,
            "sort": 0,
            "value_type": "individual"
          },
          "type": "graph",
          "xaxis": {
            "buckets": null,
            "mode": "time",
            "name": null,
            "show": true,
            "values": []
          },
          "yaxes": [
            {
              "format": "short",
              "label": null,
              "logBase": 1,
              "max": null,
              "min": null,
              "show": true
            },
            {
              "format": "short",
              "label": null,
              "logBase": 1,
              "max": null,
              "min": null,
              "show": true
            }
          ],
          "yaxis": {
            "align": false,
            "alignLevel": null
          }
        },
        {
          "aliasColors": {},
          "bars": false,
          "dashLength": 10,
          "dashes": false,
          "datasource": null,
          "fieldConfig": {
            "defaults": {
              "custom": {}
            },
            "overrides": []
          },
          "fill": 1,
          "fillGradient": 0,
          "gridPos": {
            "h": 8,
            "w": 12,
            "x": 12,
            "y": 0
          },
          "hiddenSeries": false,
          "id": 4,
          "legend": {
            "avg": false,
            "current": false,
            "max": false,
            "min": false,
            "show": true,
            "total": false,
            "values": false
          },
          "lines": true,
          "linewidth": 1,
          "nullPointMode": "null",
          "options": {
            "alertThreshold": true
          },
          "percentage": false,
          "pluginVersion": "7.2.0",
          "pointradius": 2,
          "points": false,
          "renderer": "flot",
          "seriesOverrides": [],
          "spaceLength": 10,
          "stack": false,
          "steppedLine": false,
          "targets": [
            {
              "expr": "sum(container_memory_usage_bytes{namespace=\\"easyshop\\", container!=\\"\\"}) by (container)",
              "interval": "",
              "legendFormat": "{{container}}",
              "refId": "A"
            }
          ],
          "thresholds": [],
          "timeFrom": null,
          "timeRegions": [],
          "timeShift": null,
          "title": "Memory Usage",
          "tooltip": {
            "shared": true,
            "sort": 0,
            "value_type": "individual"
          },
          "type": "graph",
          "xaxis": {
            "buckets": null,
            "mode": "time",
            "name": null,
            "show": true,
            "values": []
          },
          "yaxes": [
            {
              "format": "bytes",
              "label": null,
              "logBase": 1,
              "max": null,
              "min": null,
              "show": true
            },
            {
              "format": "short",
              "label": null,
              "logBase": 1,
              "max": null,
              "min": null,
              "show": true
            }
          ],
          "yaxis": {
            "align": false,
            "alignLevel": null
          }
        }
      ],
      "refresh": "10s",
      "schemaVersion": 26,
      "style": "dark",
      "tags": [],
      "templating": {
        "list": []
      },
      "time": {
        "from": "now-6h",
        "to": "now"
      },
      "timepicker": {},
      "timezone": "",
      "title": "EasyShop Dashboard",
      "uid": "easyshop",
      "version": 1
    }
EOF
            
            # Get Grafana LoadBalancer URL
            echo "Grafana URL: https://grafana.iemafzalhassan.tech"
            echo "Prometheus URL: https://prometheus.iemafzalhassan.tech"
            echo "Alertmanager URL: https://alertmanager.iemafzalhassan.tech"
            echo "Grafana Username: admin"
            echo "Grafana Password: admin"
            
            # Display the actual LoadBalancer URL as well
            GRAFANA_LB=$(kubectl get svc prometheus-grafana -n monitoring -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || echo "Not available yet")
            echo "Grafana LoadBalancer URL: http://$GRAFANA_LB"
            
            # Get the NGINX Ingress Controller LoadBalancer URL
            NGINX_LB=$(kubectl get svc ingress-nginx-controller -n ingress-nginx -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || echo "Not available yet")
            echo "NGINX Ingress LoadBalancer: $NGINX_LB"
            
            # Provide DNS setup instructions
            if [ "$NGINX_LB" != "Not available yet" ]; then
                echo ""
                echo "===== IMPORTANT DNS SETUP INSTRUCTIONS ====="
                echo "To access monitoring tools, create the following DNS records:"
                echo "1. Create a CNAME record for grafana.iemafzalhassan.tech pointing to $NGINX_LB"
                echo "2. Create a CNAME record for prometheus.iemafzalhassan.tech pointing to $NGINX_LB"
                echo "3. Create a CNAME record for alertmanager.iemafzalhassan.tech pointing to $NGINX_LB"
                echo "============================================"
            fi
            
            # Verify monitoring setup
            echo "Verifying monitoring setup..."
            kubectl get pods -n monitoring
            kubectl get svc -n monitoring
            kubectl get ingress -n monitoring
        '''
    }
}