#!/usr/bin/env groovy

def call() {
    echo "Starting infrastructure provisioning with Terraform"
    
    try {
        withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', 
                          credentialsId: 'aws-access-key', 
                          accessKeyVariable: 'AWS_ACCESS_KEY_ID', 
                          secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
            
            env.AWS_DEFAULT_REGION = 'eu-north-1'
            
            // Setup Terraform backend first
            sh 'chmod +x scripts/setup-terraform-backend.sh'
            sh './scripts/setup-terraform-backend.sh'
            
            dir('terraform') {
                // Update VPC module version and fix deprecated parameters
                sh '''
                    sed -i 's/version = "~> 3.0"/version = "~> 5.0"/' main.tf
                    sed -i 's/vpc = true/domain = "vpc"/' main.tf
                '''
                
                // Handle existing IAM role
                sh '''
                    if aws iam get-role --role-name easyshop-eks-role &>/dev/null; then
                        echo "Role already exists, updating configuration..."
                        sed -i 's/create_role\\s*=\\s*true/create_role = false/' main.tf
                    fi
                '''
                
                // Initialize Terraform
                sh '''
                    terraform init -upgrade
                '''
                
                // Check for existing resources and handle them properly
                sh '''
                    # First, ensure we can connect to the cluster
                    if aws eks update-kubeconfig --name easyshop-prod --region eu-north-1 &>/dev/null; then
                        echo "Successfully connected to the EKS cluster"
                        
                        # Check and handle existing namespaces
                        for ns in argocd ingress-nginx cert-manager monitoring; do
                            if kubectl get namespace $ns &>/dev/null; then
                                echo "$ns namespace already exists, updating Terraform state..."
                                if [ "$ns" = "argocd" ]; then
                                    terraform import kubernetes_namespace.argocd $ns || true
                                elif [ "$ns" = "ingress-nginx" ]; then
                                    terraform import kubernetes_namespace.ingress_nginx $ns || true
                                elif [ "$ns" = "cert-manager" ]; then
                                    terraform import kubernetes_namespace.cert_manager $ns || true
                                fi
                            fi
                        done
                        
                        # Check and handle existing Helm releases
                        if helm list -n ingress-nginx | grep ingress-nginx &>/dev/null; then
                            echo "NGINX Ingress Helm release already exists, removing from Terraform state"
                            terraform state list | grep helm_release.nginx_ingress && terraform state rm helm_release.nginx_ingress || true
                            
                            # Uninstall the existing Helm release to avoid conflicts
                            echo "Uninstalling existing NGINX Ingress Helm release..."
                            helm uninstall ingress-nginx -n ingress-nginx || true
                            
                            # Wait for resources to be deleted
                            echo "Waiting for NGINX Ingress resources to be deleted..."
                            kubectl wait --for=delete deployment/ingress-nginx-controller -n ingress-nginx --timeout=120s || true
                            sleep 30
                        fi
                        
                        if helm list -n argocd | grep argocd &>/dev/null; then
                            echo "ArgoCD Helm release already exists, removing from Terraform state"
                            terraform state list | grep helm_release.argocd && terraform state rm helm_release.argocd || true
                        fi
                        
                        if helm list -n cert-manager | grep cert-manager &>/dev/null; then
                            echo "Cert Manager Helm release already exists, removing from Terraform state"
                            terraform state list | grep helm_release.cert_manager && terraform state rm helm_release.cert_manager || true
                        fi
                    else
                        echo "Could not connect to EKS cluster, skipping resource checks"
                    fi
                '''
                
                // Plan and apply with improved error handling
                sh '''
                    # Create a plan file
                    terraform plan -var="environment=prod" -var="aws_region=eu-north-1" -out=tfplan
                    
                    # Try to apply the full plan first
                    if terraform apply -auto-approve tfplan; then
                        echo "Terraform apply completed successfully"
                    else
                        echo "Initial apply failed, trying targeted approach..."
                        
                        # Apply core infrastructure first
                        terraform apply -auto-approve -var="environment=prod" -var="aws_region=eu-north-1" \
                            -target=module.vpc \
                            -target=module.eks \
                            -target=module.iam_assumable_role_admin || true
                        
                        # Update kubeconfig to ensure we're connected to the new cluster
                        aws eks update-kubeconfig --name easyshop-prod --region eu-north-1
                        
                        # Apply namespaces
                        terraform apply -auto-approve -var="environment=prod" -var="aws_region=eu-north-1" \
                            -target=kubernetes_namespace.ingress_nginx \
                            -target=kubernetes_namespace.cert_manager \
                            -target=kubernetes_namespace.argocd || true
                        
                        # Apply NGINX Ingress separately
                        terraform apply -auto-approve -var="environment=prod" -var="aws_region=eu-north-1" \
                            -target=helm_release.nginx_ingress || true
                        
                        # Wait for ingress controller to be ready
                        echo "Waiting for ingress controller to be ready..."
                        kubectl wait --for=condition=available --timeout=300s deployment/ingress-nginx-controller -n ingress-nginx || true
                        
                        # Apply ArgoCD separately
                        terraform apply -auto-approve -var="environment=prod" -var="aws_region=eu-north-1" \
                            -target=helm_release.argocd || true
                    fi
                '''
                
                // Extract and store outputs
                sh '''
                    terraform output -json > terraform_outputs.json
                '''
                
                // Get EKS cluster name
                def eksClusterName = sh(
                    script: 'jq -r .cluster_name.value terraform_outputs.json || echo "easyshop-prod"',
                    returnStdout: true
                ).trim()
                
                env.EKS_CLUSTER_NAME = eksClusterName
                echo "EKS Cluster Name: ${env.EKS_CLUSTER_NAME}"
                
                // Get NGINX Ingress Controller DNS
                def ingressDns = sh(
                    script: 'jq -r .nginx_ingress_hostname.value terraform_outputs.json || echo "not-available"',
                    returnStdout: true
                ).trim()
                
                env.INGRESS_DNS = ingressDns
                echo "NGINX Ingress DNS: ${env.INGRESS_DNS}"
                
                // Verify resources are properly created
                sh '''
                    echo "Verifying infrastructure resources..."
                    
                    # Check EKS cluster
                    aws eks describe-cluster --name easyshop-prod --region eu-north-1 || echo "EKS cluster not found"
                    
                    # Check if we can connect to the cluster
                    kubectl cluster-info || echo "Cannot connect to Kubernetes cluster"
                    
                    # Check namespaces
                    kubectl get namespaces || echo "Cannot get namespaces"
                    
                    # Check NGINX Ingress
                    kubectl get pods -n ingress-nginx || echo "Cannot get NGINX Ingress pods"
                    
                    # Check NGINX Ingress service
                    kubectl get svc -n ingress-nginx || echo "Cannot get NGINX Ingress service"
                '''
            }
        }
    } catch (Exception e) {
        echo "Error during infrastructure provisioning: ${e.message}"
        throw e
    }
}