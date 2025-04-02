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
        
        # Create HTML template file for Trivy
        cat > /tmp/trivy-template.tpl << 'EOL'
<!DOCTYPE html>
<html>
  <head>
    <meta charset="UTF-8">
    <title>Trivy Scan Report - {{- escapeXML .Target -}}</title>
    <style>
      body {
        font-family: 'Helvetica Neue', Helvetica, Arial, sans-serif;
        color: #333;
        font-size: 14px;
        padding: 20px;
      }
      h1 {
        font-size: 24px;
        font-weight: 600;
        margin-bottom: 20px;
      }
      h2 {
        font-size: 20px;
        font-weight: 500;
        margin-top: 30px;
        margin-bottom: 15px;
      }
      table {
        border-collapse: collapse;
        width: 100%;
        margin-bottom: 20px;
      }
      th, td {
        text-align: left;
        padding: 8px;
        border: 1px solid #ddd;
      }
      th {
        background-color: #f2f2f2;
        font-weight: 500;
      }
      tr:nth-child(even) {
        background-color: #f9f9f9;
      }
      .critical {
        background-color: #fde0dc;
      }
      .high {
        background-color: #fef9e7;
      }
      .medium {
        background-color: #eaf6f9;
      }
      .low {
        background-color: #f0f4f8;
      }
      .unknown {
        background-color: #f5f5f5;
      }
      .summary {
        margin-bottom: 20px;
      }
      .summary-table {
        width: auto;
        margin-bottom: 20px;
      }
    </style>
  </head>
  <body>
    <h1>Trivy Scan Report - {{- escapeXML .Target -}}</h1>
    <div class="summary">
      <h2>Summary</h2>
      <table class="summary-table">
        <tr>
          <th>Target</th>
          <td>{{- escapeXML .Target -}}</td>
        </tr>
        <tr>
          <th>Total Vulnerabilities</th>
          <td>{{ $total := 0 }}{{ range .Results }}{{ range .Vulnerabilities }}{{ $total = add $total 1 }}{{ end }}{{ end }}{{ $total }}</td>
        </tr>
      </table>
    </div>
    {{ range .Results }}
    <h2>{{ escapeXML .Target }}</h2>
    {{ if .Vulnerabilities }}
    <table>
      <tr>
        <th>Package</th>
        <th>Vulnerability ID</th>
        <th>Severity</th>
        <th>Installed Version</th>
        <th>Fixed Version</th>
        <th>Description</th>
      </tr>
      {{ range .Vulnerabilities }}
      <tr class="{{ lower .Severity }}">
        <td>{{ escapeXML .PkgName }}</td>
        <td>{{ escapeXML .VulnerabilityID }}</td>
        <td>{{ escapeXML .Severity }}</td>
        <td>{{ escapeXML .InstalledVersion }}</td>
        <td>{{ escapeXML .FixedVersion }}</td>
        <td>{{ escapeXML .Description }}</td>
      </tr>
      {{ end }}
    </table>
    {{ else }}
    <p>No vulnerabilities found.</p>
    {{ end }}
    {{ end }}
  </body>
</html>
EOL
        
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