def call(Map config = [:]) {
    // Default tool versions
    def tools = config.tools ?: ['aws', 'kubectl', 'eksctl', 'terraform', 'helm']
    def versions = config.versions ?: [
        'aws': '2.0.0',
        'kubectl': '1.24.0',
        'eksctl': '0.100.0',
        'terraform': '1.5.7',
        'helm': '3.12.3'
    ]
    def configureAws = config.configureAws ?: true
    
    // Convert tools array to a space-separated string for sh script
    def toolsStr = tools.join(" ")
    
    sh """#!/bin/bash
        set -e
        mkdir -p \$HOME/bin
        export PATH=\$HOME/bin:\$PATH
        
        # Function to compare versions
        version_gt() {
            test "\$(printf '%s\\n' "\$1" "\$2" | sort -V | head -n1)" != "\$1"
        }
        
        # AWS CLI
        if echo "${toolsStr}" | grep -q "aws"; then
            if ! command -v aws &> /dev/null; then
                echo "Installing AWS CLI..."
                curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "awscliv2.zip"
                unzip -q awscliv2.zip
                ./aws/install -i \$HOME/aws-cli -b \$HOME/bin --update
                rm -rf aws awscliv2.zip
            else
                AWS_VERSION=\$(aws --version | cut -d' ' -f1 | cut -d'/' -f2)
                if version_gt "${versions.aws}" "\$AWS_VERSION"; then
                    echo "Updating AWS CLI from version \$AWS_VERSION to ${versions.aws}..."
                    curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "awscliv2.zip"
                    unzip -q awscliv2.zip
                    ./aws/install -i \$HOME/aws-cli -b \$HOME/bin --update
                    rm -rf aws awscliv2.zip
                else
                    echo "AWS CLI version \$AWS_VERSION is already installed and up to date"
                fi
            fi
        fi
        
        # kubectl
        if echo "${toolsStr}" | grep -q "kubectl"; then
            echo "Cleaning up any existing kubectl installations in user directory..."
            rm -rf "\$HOME/bin/kubectl" 2>/dev/null || true
            
            echo "Installing kubectl..."
            curl -LO "https://dl.k8s.io/release/\$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl"
            chmod +x kubectl
            cp -f kubectl \$HOME/bin/ 2>/dev/null || true
            rm -f kubectl
            
            # Verify installation
            echo "Verifying kubectl installation..."
            \$HOME/bin/kubectl version --client || echo "kubectl installation failed"
        fi
        
        # eksctl
        if echo "${toolsStr}" | grep -q "eksctl"; then
            echo "Cleaning up any existing eksctl installations..."
            rm -rf "\$HOME/bin/eksctl"
            which eksctl && rm -f "\$(which eksctl)" || true
            
            echo "Installing eksctl..."
            curl --silent --location "https://github.com/weaveworks/eksctl/releases/latest/download/eksctl_\$(uname -s)_amd64.tar.gz" | tar xz -C /tmp
            mv -f /tmp/eksctl \$HOME/bin/
            chmod +x \$HOME/bin/eksctl
            
            # Verify installation
            echo "Verifying eksctl installation..."
            \$HOME/bin/eksctl version || echo "eksctl installation failed"
        fi
        
        # Terraform
        if echo "${toolsStr}" | grep -q "terraform"; then
            TERRAFORM_VERSION="${versions.terraform}"
            
            # Super aggressive cleanup for terraform
            echo "Performing deep cleanup of any existing terraform installations..."
            find \$HOME/bin -name "terraform*" -exec rm -rf {} \\; 2>/dev/null || true
            find /tmp -name "terraform*" -exec rm -rf {} \\; 2>/dev/null || true
            which terraform && rm -f "\$(which terraform)" 2>/dev/null || true
            
            # Create a clean directory for terraform
            rm -rf \$HOME/bin/terraform* 2>/dev/null || true
            
            echo "Installing Terraform \$TERRAFORM_VERSION..."
            # Download to /tmp to avoid any permission issues
            cd /tmp
            curl -fsSL "https://releases.hashicorp.com/terraform/\${TERRAFORM_VERSION}/terraform_\${TERRAFORM_VERSION}_linux_amd64.zip" -o terraform.zip
            unzip -q -o terraform.zip
            chmod +x terraform
            cp -f terraform \$HOME/bin/
            rm -f terraform terraform.zip
            cd -
            
            # Verify installation
            echo "Verifying Terraform installation..."
            \$HOME/bin/terraform version || echo "Terraform installation failed"
        fi
        
        # Helm
        if echo "${toolsStr}" | grep -q "helm"; then
            echo "Cleaning up any existing helm installations..."
            rm -rf "\$HOME/bin/helm"
            which helm && rm -f "\$(which helm)" || true
            
            HELM_VERSION="${versions.helm}"
            echo "Installing Helm \$HELM_VERSION..."
            curl -fsSL "https://get.helm.sh/helm-v\${HELM_VERSION}-linux-amd64.tar.gz" | tar -zxf - -C /tmp
            mv -f /tmp/linux-amd64/helm \$HOME/bin/
            chmod +x \$HOME/bin/helm
            
            # Verify installation
            echo "Verifying Helm installation..."
            \$HOME/bin/helm version --short || echo "Helm installation failed"
        fi
    """
    
    // Configure AWS if needed
    if (configureAws) {
        sh """
            mkdir -p ~/.aws
            
            cat > ~/.aws/credentials << EOF
[default]
aws_access_key_id = ${env.AWS_ACCESS_KEY_ID}
aws_secret_access_key = ${env.AWS_SECRET_ACCESS_KEY}
region = ${env.AWS_DEFAULT_REGION}
EOF
            
            cat > ~/.aws/config << EOF
[default]
region = ${env.AWS_DEFAULT_REGION}
output = json
EOF
            
            chmod 600 ~/.aws/credentials
            chmod 600 ~/.aws/config
        """
    }
}