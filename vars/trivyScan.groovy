#!/usr/bin/env groovy

/**
 * Run Trivy security scan on Docker image
 *
 * @param imageName The name of the Docker image
 * @param imageTag The tag for the Docker image
 * @param threshold The maximum number of vulnerabilities allowed
 * @param severity The severity levels to check for
 * @param installTrivy Whether to attempt installing Trivy if not found
 */
def call(Map config = [:]) {
    def imageName = config.imageName ?: error("Image name is required")
    def imageTag = config.imageTag ?: 'latest'
    def threshold = config.threshold ?: 100
    def severity = config.severity ?: 'HIGH,CRITICAL'
    def installTrivy = config.installTrivy ?: true
    
    echo "Running Trivy security scan on ${imageName}:${imageTag}"
    
    // Create directory for results
    sh "mkdir -p trivy-results"
    
    // Check if Trivy is installed
    def trivyInstalled = sh(script: "which trivy || true", returnStdout: true).trim()
    
    // Install Trivy if not found and installation is requested
    if (!trivyInstalled && installTrivy) {
        echo "Trivy not found. Attempting to install..."
        
        try {
            sh """
                # For Debian/Ubuntu
                if command -v apt-get &> /dev/null; then
                    sudo apt-get update
                    sudo apt-get install -y wget apt-transport-https gnupg lsb-release
                    wget -qO - https://aquasecurity.github.io/trivy-repo/deb/public.key | sudo apt-key add -
                    echo deb https://aquasecurity.github.io/trivy-repo/deb \$(lsb_release -sc) main | sudo tee -a /etc/apt/sources.list.d/trivy.list
                    sudo apt-get update
                    sudo apt-get install -y trivy
                # For macOS
                elif command -v brew &> /dev/null; then
                    brew install aquasecurity/trivy/trivy
                # For Amazon Linux, CentOS, etc.
                elif command -v yum &> /dev/null; then
                    sudo yum install -y wget
                    sudo rpm -ivh https://github.com/aquasecurity/trivy/releases/download/v0.38.3/trivy_0.38.3_Linux-64bit.rpm
                else
                    echo "Unsupported OS for automatic Trivy installation"
                    exit 1
                fi
            """
            echo "Trivy installed successfully"
        } catch (Exception e) {
            echo "Failed to install Trivy: ${e.message}"
            error "Trivy installation failed. Please install Trivy manually on the Jenkins server."
        }
    } else if (!trivyInstalled) {
        error "Trivy not found. Please install Trivy on the Jenkins server or set 'installTrivy: true' in the function call."
    }
    
    // Run Trivy scan
    def exitCode = sh(
        script: """
            trivy image --format json --output trivy-results/${imageName.replaceAll('/', '-')}-${imageTag}.json \
            --severity ${severity} ${imageName}:${imageTag}
            
            # Create a simple HTML template if the template file doesn't exist
            if [ ! -f /tmp/trivy-template.tpl ]; then
                echo '<html><head><title>Trivy Scan Results</title></head><body><h1>Trivy Scan Results</h1><pre>{{ range . }}<h2>{{ .Target }}</h2>{{ range .Vulnerabilities }}<div>{{ .VulnerabilityID }}: {{ .Title }} ({{ .Severity }})</div>{{ end }}{{ end }}</pre></body></html>' > /tmp/trivy-template.tpl
            fi
            
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
        error "Security scan failed: vulnerability count exceeds threshold or Trivy encountered an error"
    }
    
    echo "Security scan completed successfully"
}