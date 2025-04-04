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
        echo "Trivy not found. Attempting to install without sudo..."
        
        try {
            // Create a local bin directory in workspace
            sh "mkdir -p \${WORKSPACE}/bin"
            
            // Add the local bin to PATH
            def localBinPath = "\${WORKSPACE}/bin"
            env.PATH = "${localBinPath}:${env.PATH}"
            
            // Download and install Trivy directly to the local bin
            sh """
                # Detect OS and architecture
                OS=\$(uname -s | tr '[:upper:]' '[:lower:]')
                ARCH=\$(uname -m)
                
                if [ "\$ARCH" = "x86_64" ]; then
                    ARCH="64bit"
                elif [ "\$ARCH" = "aarch64" ] || [ "\$ARCH" = "arm64" ]; then
                    ARCH="ARM64"
                else
                    echo "Unsupported architecture: \$ARCH"
                    exit 1
                fi
                
                # Set Trivy version
                TRIVY_VERSION="0.38.3"
                
                if [ "\$OS" = "linux" ]; then
                    TRIVY_FILENAME="trivy_\${TRIVY_VERSION}_Linux-\${ARCH}.tar.gz"
                elif [ "\$OS" = "darwin" ]; then
                    TRIVY_FILENAME="trivy_\${TRIVY_VERSION}_macOS-\${ARCH}.tar.gz"
                else
                    echo "Unsupported OS: \$OS"
                    exit 1
                fi
                
                # Download Trivy
                curl -sfL -o /tmp/trivy.tar.gz https://github.com/aquasecurity/trivy/releases/download/v\${TRIVY_VERSION}/\${TRIVY_FILENAME}
                
                # Extract Trivy to local bin
                tar -xzf /tmp/trivy.tar.gz -C /tmp
                mv /tmp/trivy ${localBinPath}/
                chmod +x ${localBinPath}/trivy
                
                # Verify installation
                ${localBinPath}/trivy --version
            """
            echo "Trivy installed successfully to ${localBinPath}"
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