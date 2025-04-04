#!/usr/bin/env groovy

/**
 * Run Trivy security scan on Docker image
 *
 * @param imageName The name of the Docker image
 * @param imageTag The tag for the Docker image
 * @param threshold The maximum number of vulnerabilities allowed
 * @param severity The severity levels to check for
 */
def call(Map config = [:]) {
    def imageName = config.imageName ?: error("Image name is required")
    def imageTag = config.imageTag ?: 'latest'
    def threshold = config.threshold ?: 100
    def severity = config.severity ?: 'HIGH,CRITICAL'
    
    echo "Running Trivy security scan on ${imageName}:${imageTag}"
    
    // Create directory for results
    sh "mkdir -p trivy-results"
    
    // Run Trivy scan
    def exitCode = sh(
        script: """
            trivy image --format json --output trivy-results/${imageName.replaceAll('/', '-')}-${imageTag}.json \
            --severity ${severity} ${imageName}:${imageTag}
            
            trivy image --format template --template '@/tmp/trivy-template.tpl' \
            --output trivy-results/${imageName.replaceAll('/', '-')}-${imageTag}.html \
            --severity ${severity} ${imageName}:${imageTag}
            
            # Count vulnerabilities
            VULN_COUNT=\$(trivy image --severity ${severity} --quiet ${imageName}:${imageTag} | grep -c -i vulnerability || true)
            echo "Found \$VULN_COUNT vulnerabilities"
            
            if [ \$VULN_COUNT -gt ${threshold} ]; then
                echo "Vulnerability count \$VULN_COUNT exceeds threshold ${threshold}"
                exit 1
            fi
        """,
        returnStatus: true
    )
    
    if (exitCode != 0) {
        error "Security scan failed: vulnerability count exceeds threshold"
    }
    
    echo "Security scan completed successfully"
}