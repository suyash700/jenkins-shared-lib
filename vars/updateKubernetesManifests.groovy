def call(Map config = [:]) {
    def imageName = config.imageName ?: 'iemafzal/easyshop'
    def imageTag = config.imageTag ?: 'latest'
    def manifestPath = config.manifestPath ?: 'kubernetes/08-easyshop-deployment.yaml'
    def gitCredentialsId = config.gitCredentialsId ?: 'github-ssh-key'
    
    withCredentials([sshUserPrivateKey(credentialsId: gitCredentialsId, keyFileVariable: 'SSH_KEY')]) {
        sh """
            # Configure Git
            git config --global user.email "jenkins@example.com"
            git config --global user.name "Jenkins CI"
            
            # Configure Git credentials helper
            git config --global credential.helper store
            
            # Update image tag in deployment file
            if [[ "\$(uname)" == "Darwin" ]]; then
                # MacOS version of sed
                sed -i '' 's|image: ${imageName}:.*|image: ${imageName}:${imageTag}|' ${manifestPath}
            else
                # Linux version of sed
                sed -i 's|image: ${imageName}:.*|image: ${imageName}:${imageTag}|' ${manifestPath}
            fi
            
            # Commit and push changes
            GIT_SSH_COMMAND="ssh -i \${SSH_KEY} -o StrictHostKeyChecking=no" git add ${manifestPath}
            GIT_SSH_COMMAND="ssh -i \${SSH_KEY} -o StrictHostKeyChecking=no" git commit -m "Update image to ${imageTag} [skip ci]"
            GIT_SSH_COMMAND="ssh -i \${SSH_KEY} -o StrictHostKeyChecking=no" git push origin HEAD:main
        """
    }
}