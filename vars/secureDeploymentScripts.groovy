def call(Map config = [:]) {
    def scripts = config.scripts ?: []
    def defaultRegion = config.defaultRegion ?: 'eu-north-1'
    
    sh """#!/bin/bash
        set -e
        
        # Function to check if a file contains hardcoded AWS credentials
        function check_for_credentials {
            local file=\$1
            if grep -q "AWS_ACCESS_KEY_ID=" "\$file" || grep -q "AWS_SECRET_ACCESS_KEY=" "\$file"; then
                return 0  # Found credentials
            fi
            return 1  # No credentials found
        }
        
        # Function to update a script to use environment variables
        function secure_script {
            local file=\$1
            
            if [ ! -f "\$file" ]; then
                echo "File not found: \$file"
                return
            fi
            
            echo "Checking \$file for hardcoded credentials..."
            
            if check_for_credentials "\$file"; then
                echo "Securing \$file..."
                
                # Create backup
                cp "\$file" "\${file}.bak"
                
                # Replace hardcoded credentials with environment variables
                sed -i.tmp 's/AWS_ACCESS_KEY_ID=.*/AWS_ACCESS_KEY_ID=\${AWS_ACCESS_KEY_ID}/g' "\$file"
                sed -i.tmp 's/AWS_SECRET_ACCESS_KEY=.*/AWS_SECRET_ACCESS_KEY=\${AWS_SECRET_ACCESS_KEY}/g' "\$file"
                sed -i.tmp 's/AWS_DEFAULT_REGION=.*/AWS_DEFAULT_REGION=\${AWS_DEFAULT_REGION}/g' "\$file"
                
                # Add check for environment variables at the beginning of the script
                cat > "\${file}.new" << EOF
#!/bin/bash
set -e

# Check for required environment variables
if [ -z "\\\${AWS_ACCESS_KEY_ID}" ] || [ -z "\\\${AWS_SECRET_ACCESS_KEY}" ]; then
    echo "Error: AWS credentials not set. Please set AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY environment variables."
    exit 1
fi

if [ -z "\\\${AWS_DEFAULT_REGION}" ]; then
    echo "Warning: AWS_DEFAULT_REGION not set. Using default region."
    export AWS_DEFAULT_REGION="${defaultRegion}"
fi

EOF
                
                # Append the rest of the script (excluding the first line with shebang)
                tail -n +2 "\$file" >> "\${file}.new"
                
                # Replace the original file
                mv "\${file}.new" "\$file"
                chmod +x "\$file"
                
                # Remove temporary files
                rm -f "\${file}.tmp"
                
                echo "Script \$file secured successfully."
            else
                echo "No hardcoded credentials found in \$file."
            fi
        }
        
        # Secure each script in the list
        ${scripts.collect { "secure_script \"${it}\"" }.join('\n        ')}
        
        echo "All deployment scripts secured."
    """
}