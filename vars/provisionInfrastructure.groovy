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
                
                // Update the ArgoCD handling section in the provisionInfrastructure.groovy file
                // Around line 38-58
                
                // Check if ArgoCD namespace exists and handle it
                sh '''
                    # Check if we can access the Kubernetes cluster
                    if kubectl get namespaces &>/dev/null; then
                        # Check if ArgoCD namespace exists
                        if kubectl get namespace argocd &>/dev/null; then
                            echo "ArgoCD namespace already exists, updating Terraform state..."
                            # Import the namespace into Terraform state if it's not already there
                            terraform import kubernetes_namespace.argocd argocd || true
                        fi
                        
                        # Check if ArgoCD Helm release exists
                        if helm list -n argocd | grep argocd &>/dev/null; then
                            echo "ArgoCD Helm release already exists, removing from Terraform state"
                            terraform state list | grep helm_release.argocd && terraform state rm helm_release.argocd || true
                        fi
                        
                        # Check if ingress-nginx namespace exists
                        if kubectl get namespace ingress-nginx &>/dev/null; then
                            echo "ingress-nginx namespace already exists, updating Terraform state..."
                            # Import the namespace into Terraform state if it's not already there
                            terraform import kubernetes_namespace.ingress_nginx ingress-nginx || true
                        fi
                        
                        # Check if NGINX Ingress Helm release exists
                        if helm list -n ingress-nginx | grep ingress-nginx &>/dev/null; then
                            echo "NGINX Ingress Helm release already exists, updating Terraform state"
                            terraform state list | grep helm_release.nginx_ingress && terraform state rm helm_release.nginx_ingress || true
                        fi
                    fi
                '''
                
                // Plan and apply with improved error handling
                sh '''
                    terraform plan -var="environment=prod" -var="aws_region=eu-north-1" -out=tfplan
                    # Apply with targeted approach to avoid namespace issues
                    terraform apply -auto-approve tfplan || {
                        echo "Initial apply failed, trying targeted approach..."
                        # Skip the namespace creation and apply everything else
                        terraform apply -auto-approve -var="environment=prod" -var="aws_region=eu-north-1" -target=module.vpc -target=module.eks -target=module.iam_assumable_role_admin || true
                        
                        # Apply ingress-nginx separately
                        terraform apply -auto-approve -var="environment=prod" -var="aws_region=eu-north-1" -target=kubernetes_namespace.ingress_nginx -target=helm_release.nginx_ingress || true
                        
                        # Apply cert-manager separately
                        terraform apply -auto-approve -var="environment=prod" -var="aws_region=eu-north-1" -target=kubernetes_namespace.cert_manager || true
                        
                        # Wait for ingress controller to be ready
                        echo "Waiting for ingress controller to be ready..."
                        kubectl wait --for=condition=available --timeout=300s deployment/ingress-nginx-controller -n ingress-nginx || true
                    }
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
            }
        }
    } catch (Exception e) {
        echo "Error during infrastructure provisioning: ${e.message}"
        throw e
    }
}