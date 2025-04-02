def call(Map config = [:]) {
    def imageName = config.imageName ?: ''
    def imageTag = config.imageTag ?: 'latest'
    def threshold = config.threshold ?: 10
    def severity = config.severity ?: 'HIGH,CRITICAL'
    def outputDir = config.outputDir ?: 'trivy-results'
    
    sh """
        # Install Trivy if not already installed
        if ! command -v trivy &> /dev/null; then
            echo "Installing Trivy..."
            curl -sfL https://raw.githubusercontent.com/aquasecurity/trivy/main/contrib/install.sh | sh -s -- -b \$HOME/bin v0.48.0
            export PATH=\$HOME/bin:\$PATH
        fi
        
        # Create directory for scan results
        mkdir -p ${outputDir}
        
        echo "Scanning ${imageName}:${imageTag} for vulnerabilities..."
        # Scan the image and save results
        trivy image --exit-code 0 --severity ${severity} --timeout 15m \\
          --output ${outputDir}/scan-\$(echo ${imageName} | tr '/' '-')-${imageTag}.json --format json \\
          ${imageName}:${imageTag} || true
        
        # Generate HTML report
        trivy image --format template --template "@/tmp/trivy-template.tpl" \\
          --output ${outputDir}/scan-\$(echo ${imageName} | tr '/' '-')-${imageTag}.html \\
          ${imageName}:${imageTag} || true
        
        # Count critical vulnerabilities
        CRITICAL_VULNS=\$(cat ${outputDir}/scan-\$(echo ${imageName} | tr '/' '-')-${imageTag}.json | jq '.Results[] | select(.Vulnerabilities != null) | .Vulnerabilities[] | select(.Severity == "CRITICAL")' | wc -l)
        
        echo "Found \$CRITICAL_VULNS critical vulnerabilities"
        
        # Fail the build if there are too many critical vulnerabilities
        if [ \$CRITICAL_VULNS -gt ${threshold} ]; then
            echo "Too many critical vulnerabilities found. Failing the build."
            exit 1
        else
            echo "Number of critical vulnerabilities is within acceptable threshold."
        fi
    """
}