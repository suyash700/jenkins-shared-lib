#!/usr/bin/env groovy

def call() {
    echo "Running security scan with Trivy"
    
    try {
        sh '''
            echo "Installing Trivy..."
            
            # Create a user-accessible directory for binaries if it doesn't exist
            mkdir -p ${WORKSPACE}/bin
            
            # Check if Trivy is already installed
            if ! command -v ${WORKSPACE}/bin/trivy &> /dev/null; then
                # Download and install Trivy to the workspace bin directory
                curl -sfL https://raw.githubusercontent.com/aquasecurity/trivy/main/contrib/install.sh | \
                sh -s -- -b ${WORKSPACE}/bin
                
                # Make it executable
                chmod +x ${WORKSPACE}/bin/trivy
            fi
            
            # Add the bin directory to PATH
            export PATH=${WORKSPACE}/bin:$PATH
            
            # Verify Trivy installation
            ${WORKSPACE}/bin/trivy --version
            
            # Scan container images
            echo "Scanning container images..."
            for image in $(kubectl get pods -A -o jsonpath="{.items[*].spec.containers[*].image}" | tr -s '[[:space:]]' '\\n' | sort | uniq); do
                echo "Scanning image: $image"
                ${WORKSPACE}/bin/trivy image --severity HIGH,CRITICAL --exit-code 0 $image || echo "Vulnerabilities found in $image"
            done
            
            # Scan Kubernetes manifests
            echo "Scanning Kubernetes manifests..."
            find kubernetes -name "*.yaml" -type f -exec ${WORKSPACE}/bin/trivy config --severity HIGH,CRITICAL --exit-code 0 {} \\; || echo "Vulnerabilities found in Kubernetes manifests"
            
            # Scan Terraform files
            echo "Scanning Terraform files..."
            find terraform -name "*.tf" -type f -exec ${WORKSPACE}/bin/trivy config --severity HIGH,CRITICAL --exit-code 0 {} \\; || echo "Vulnerabilities found in Terraform files"
            
            echo "Security scan completed"
        '''
    } catch (Exception e) {
        echo "Error during security scan: ${e.message}"
        // Don't fail the pipeline for security scan issues
        // Just report them
    }
}