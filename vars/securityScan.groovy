#!/usr/bin/env groovy

def call() {
    echo "Running security scan with Trivy"
    
    try {
        // Install Trivy if not already installed
        sh '''
            if ! command -v trivy &> /dev/null; then
                echo "Installing Trivy..."
                curl -sfL https://raw.githubusercontent.com/aquasecurity/trivy/main/contrib/install.sh | sh -s -- -b /usr/local/bin
            fi
        '''
        
        // Scan the application image
        sh """
            trivy image --severity HIGH,CRITICAL --exit-code 0 ${env.DOCKER_IMAGE_NAME}:${env.DOCKER_IMAGE_TAG} > trivy-app-results.txt
            cat trivy-app-results.txt
        """
        
        // Scan the migration image
        sh """
            trivy image --severity HIGH,CRITICAL --exit-code 0 ${env.DOCKER_IMAGE_NAME}-migration:${env.DOCKER_IMAGE_TAG} > trivy-migration-results.txt
            cat trivy-migration-results.txt
        """
        
        // Archive the results
        archiveArtifacts artifacts: '*-results.txt', allowEmptyArchive: true
        
    } catch (Exception e) {
        echo "Error during security scan: ${e.message}"
        // Don't fail the build, but mark it as unstable
        currentBuild.result = 'UNSTABLE'
    }
}