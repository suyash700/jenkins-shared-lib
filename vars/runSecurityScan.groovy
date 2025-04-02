#!/usr/bin/env groovy

def call() {
    echo "Running security scans..."
    
    try {
        // Scan Docker images
        sh '''
            echo "Scanning Docker images with Trivy..."
            
            # Scan main application image
            trivy image --severity HIGH,CRITICAL --exit-code 0 ${DOCKER_IMAGE_NAME}:${DOCKER_IMAGE_TAG} || echo "Vulnerabilities found in main image"
            
            # Scan migration image
            trivy image --severity HIGH,CRITICAL --exit-code 0 ${DOCKER_IMAGE_NAME}-migration:${DOCKER_IMAGE_TAG} || echo "Vulnerabilities found in migration image"
            
            # Scan Kubernetes manifests
            echo "Scanning Kubernetes manifests..."
            find kubernetes -name "*.yaml" -type f -exec trivy config --severity HIGH,CRITICAL --exit-code 0 {} \\; || echo "Vulnerabilities found in Kubernetes manifests"
            
            # Scan Terraform files
            echo "Scanning Terraform files..."
            find terraform -name "*.tf" -type f -exec trivy config --severity HIGH,CRITICAL --exit-code 0 {} \\; || echo "Vulnerabilities found in Terraform files"
            
            echo "Security scan completed"
        '''
    } catch (Exception e) {
        echo "Error during security scan: ${e.message}"
        // Don't fail the pipeline for security scan issues
        // Just report them
    }
}