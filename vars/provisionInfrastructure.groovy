#!/usr/bin/env groovy

def call() {
    echo "Starting infrastructure provisioning with Terraform"
    
    try {
        withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', 
                          credentialsId: 'aws-access-key', 
                          accessKeyVariable: 'AWS_ACCESS_KEY_ID', 
                          secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
            
            env.AWS_DEFAULT_REGION = 'eu-north-1'
            
            dir('terraform') {
                sh 'terraform init -upgrade'
                
                // First, create the main configuration file
                writeFile file: 'main.tf', text: '''
module "eks" {
  source  = "terraform-aws-modules/eks/aws"
  version = "~> 18.0"

  cluster_name    = "easyshop-prod"
  cluster_version = "1.32"

  vpc_id     = module.vpc.vpc_id
  subnet_ids = module.vpc.private_subnets

  create_cloudwatch_log_group = true
  cloudwatch_log_group_retention_in_days = 30
  cluster_enabled_log_types  = ["api", "audit", "authenticator", "controllerManager", "scheduler"]

  eks_managed_node_group_defaults = {
    disk_size      = 50
    instance_types = ["t3.medium"]
  }

  eks_managed_node_groups = {
    app_nodes = {
      min_size     = 2
      max_size     = 5
      desired_size = 2
      instance_types = ["t3.medium"]
      capacity_type  = "ON_DEMAND"
    }
  }

  tags = {
    Environment = "prod"
    Project     = "EasyShop"
    Terraform   = "true"
  }
}
'''
                
                // Create versions.tf
                writeFile file: 'versions.tf', text: '''
terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 4.0"
    }
  }
  required_version = ">= 1.0"
}
'''
                
                // Re-initialize Terraform with new configurations
                sh 'terraform init -upgrade'
                
                // Plan and apply with error handling
                sh '''#!/bin/bash
                set -e
                
                # Remove any existing plan
                rm -f tfplan || true
                
                # Create new plan
                terraform plan -out=tfplan || {
                    echo "Initial plan failed, attempting targeted approach..."
                    terraform plan -target=module.eks -out=tfplan
                }
                
                # Apply the plan
                if [ -f "tfplan" ]; then
                    terraform apply -auto-approve tfplan || {
                        echo "Full apply failed, attempting targeted apply..."
                        terraform apply -auto-approve -target=module.eks
                    }
                else
                    echo "No plan file found, cannot proceed"
                    exit 1
                fi
                '''
                
                def eksClusterName = sh(script: 'terraform output -raw cluster_name || echo "easyshop-cluster"', returnStdout: true).trim()
                env.EKS_CLUSTER_NAME = eksClusterName
                
                echo "EKS Cluster Name: ${env.EKS_CLUSTER_NAME}"
            }
        }
    } catch (Exception e) {
        echo "Error during infrastructure provisioning: ${e.message}"
        throw e
    }
}
