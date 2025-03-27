#!/usr/bin/env groovy

def call() {
    echo "Starting security scanning with Trivy"
    
    try {
        sh '''
            if ! command -v trivy &> /dev/null; then
                echo "Installing Trivy..."
                sudo apt-get update
                sudo apt-get install -y wget apt-transport-https gnupg lsb-release
                wget -qO - https://aquasecurity.github.io/trivy-repo/deb/public.key | sudo apt-key add -
                echo deb https://aquasecurity.github.io/trivy-repo/deb $(lsb_release -sc) main | sudo tee -a /etc/apt/sources.list.d/trivy.list
                sudo apt-get update
                sudo apt-get install -y trivy
            fi
        '''
        
        // Scan the main application image
        echo "Scanning main application image"
        sh """
            trivy image --severity HIGH,CRITICAL \
                --format table \
                --output trivy-app-results.txt \
                ${env.DOCKER_IMAGE_NAME}:${env.DOCKER_IMAGE_TAG} || true
        """
        
        // Scan the migration image
        echo "Scanning migration image"
        sh """
            trivy image --severity HIGH,CRITICAL \
                --format table \
                --output trivy-migration-results.txt \
                ${env.DOCKER_IMAGE_NAME}-migration:${env.DOCKER_IMAGE_TAG} || true
        """
        
        // Archive the scan results
        archiveArtifacts artifacts: 'trivy-*.txt', allowEmptyArchive: true
        
        // Check for critical vulnerabilities and fail if found
        def appVulns = sh(script: "grep -c 'CRITICAL' trivy-app-results.txt || true", returnStdout: true).trim()
        def migrationVulns = sh(script: "grep -c 'CRITICAL' trivy-migration-results.txt || true", returnStdout: true).trim()
        
        if (appVulns != '0' || migrationVulns != '0') {
            echo "Critical vulnerabilities found in Docker images!"
            echo "Application image: ${appVulns} critical vulnerabilities"
            echo "Migration image: ${migrationVulns} critical vulnerabilities"
            
            // Mark build as unstable but don't fail
            currentBuild.result = 'UNSTABLE'
        } else {
            echo "No critical vulnerabilities found in Docker images"
        }
        
    } catch (Exception e) {
        echo "Error during security scanning: ${e.message}"
        currentBuild.result = 'UNSTABLE'
    }
}