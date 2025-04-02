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
    
    sh """
        mkdir -p \$HOME/bin
        
        # Function to compare versions
        version_gt() {
            test "\$(printf '%s\\n' "\$1" "\$2" | sort -V | head -n1)" != "\$1"
        }
        
        # AWS CLI
        if [[ "\${tools[@]}" =~ "aws" ]]; then
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
        if [[ "\${tools[@]}" =~ "kubectl" ]]; then
            if ! command -v kubectl &> /dev/null; then
                echo "Installing kubectl..."
                curl -LO "https://dl.k8s.io/release/\$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl"
                chmod +x kubectl
                mv ./kubectl \$HOME/bin/
            else
                KUBECTL_VERSION=\$(kubectl version --client -o json | grep -o '"gitVersion": *"[^"]*"' | head -1 | grep -o '[0-9.]*')
                if version_gt "${versions.kubectl}" "\$KUBECTL_VERSION"; then
                    echo "Updating kubectl from version \$KUBECTL_VERSION to ${versions.kubectl}..."
                    curl -LO "https://dl.k8s.io/release/\$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl"
                    chmod +x kubectl
                    mv ./kubectl \$HOME/bin/
                else
                    echo "kubectl version \$KUBECTL_VERSION is already installed and up to date"
                fi
            fi
        fi
        
        # eksctl
        if [[ "\${tools[@]}" =~ "eksctl" ]]; then
            if ! command -v eksctl &> /dev/null; then
                echo "Installing eksctl..."
                curl --silent --location "https://github.com/weaveworks/eksctl/releases/latest/download/eksctl_\$(uname -s)_amd64.tar.gz" | tar xz -C /tmp
                mv /tmp/eksctl \$HOME/bin/
                chmod +x \$HOME/bin/eksctl
            else
                EKSCTL_VERSION=\$(eksctl version | cut -d' ' -f3)
                if version_gt "${versions.eksctl}" "\$EKSCTL_VERSION"; then
                    echo "Updating eksctl from version \$EKSCTL_VERSION to ${versions.eksctl}..."
                    curl --silent --location "https://github.com/weaveworks/eksctl/releases/latest/download/eksctl_\$(uname -s)_amd64.tar.gz" | tar xz -C /tmp
                    mv /tmp/eksctl \$HOME/bin/
                    chmod +x \$HOME/bin/eksctl
                else
                    echo "eksctl version \$EKSCTL_VERSION is already installed and up to date"
                fi
            fi
        fi
        
        # Terraform
        if [[ "\${tools[@]}" =~ "terraform" ]]; then
            TERRAFORM_VERSION="${versions.terraform}"
            if ! command -v terraform &> /dev/null; then
                echo "Installing Terraform \$TERRAFORM_VERSION..."
                curl -fsSL "https://releases.hashicorp.com/terraform/\${TERRAFORM_VERSION}/terraform_\${TERRAFORM_VERSION}_linux_amd64.zip" -o terraform.zip
                unzip -q terraform.zip
                mv terraform \$HOME/bin/
                chmod +x \$HOME/bin/terraform
                rm terraform.zip
            else
                CURRENT_VERSION=\$(terraform version -json | grep -o '"terraform_version": *"[^"]*"' | grep -o '[0-9.]*')
                if [ "\$CURRENT_VERSION" != "\$TERRAFORM_VERSION" ]; then
                    echo "Updating Terraform from version \$CURRENT_VERSION to \$TERRAFORM_VERSION"
                    curl -fsSL "https://releases.hashicorp.com/terraform/\${TERRAFORM_VERSION}/terraform_\${TERRAFORM_VERSION}_linux_amd64.zip" -o terraform.zip
                    unzip -q terraform.zip
                    mv terraform \$HOME/bin/
                    chmod +x \$HOME/bin/terraform
                    rm terraform.zip
                else
                    echo "Terraform version \$CURRENT_VERSION is already installed and up to date"
                fi
            fi
        fi
        
        # Helm
        if [[ "\${tools[@]}" =~ "helm" ]]; then
            HELM_VERSION="${versions.helm}"
            if ! command -v helm &> /dev/null; then
                echo "Installing Helm \$HELM_VERSION..."
                curl -fsSL "https://get.helm.sh/helm-v\${HELM_VERSION}-linux-amd64.tar.gz" | tar -zxf - -C /tmp
                mv /tmp/linux-amd64/helm \$HOME/bin/
                chmod +x \$HOME/bin/helm
            else
                CURRENT_VERSION=\$(helm version --short | cut -d'+' -f1 | cut -d'v' -f2)
                if [ "\$CURRENT_VERSION" != "\$HELM_VERSION" ]; then
                    echo "Updating Helm from version \$CURRENT_VERSION to \$HELM_VERSION"
                    curl -fsSL "https://get.helm.sh/helm-v\${HELM_VERSION}-linux-amd64.tar.gz" | tar -zxf - -C /tmp
                    mv /tmp/linux-amd64/helm \$HOME/bin/
                    chmod +x \$HOME/bin/helm
                else
                    echo "Helm version \$CURRENT_VERSION is already installed and up to date"
                fi
            fi
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