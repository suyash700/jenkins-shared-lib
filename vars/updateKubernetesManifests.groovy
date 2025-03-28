#!/usr/bin/env groovy

def call() {
    echo "Updating Kubernetes manifests with new image tags"
    
    // Update image tags in deployment files
    sh """
        sed -i 's|image: ${env.DOCKER_IMAGE_NAME}:latest|image: ${env.DOCKER_IMAGE_NAME}:${env.DOCKER_IMAGE_TAG}|g' kubernetes/08-easyshop-deployment.yaml
        sed -i 's|image: ${env.DOCKER_IMAGE_NAME}-migration:latest|image: ${env.DOCKER_IMAGE_NAME}-migration:${env.DOCKER_IMAGE_TAG}|g' kubernetes/12-migration-job.yaml
    """
    
    // Push changes to Git repository
    withCredentials([usernamePassword(
        credentialsId: 'github-credentials',
        usernameVariable: 'GIT_USERNAME',
        passwordVariable: 'GIT_PASSWORD'
    )]) {
        sh """
            git config user.email "jenkins@example.com"
            git config user.name "Jenkins"
            
            # Create a local branch
            git checkout -b update-image-tags-${env.BUILD_NUMBER}
            
            # Apply our changes
            sed -i 's|image: ${env.DOCKER_IMAGE_NAME}:latest|image: ${env.DOCKER_IMAGE_NAME}:${env.DOCKER_IMAGE_TAG}|g' kubernetes/08-easyshop-deployment.yaml
            sed -i 's|image: ${env.DOCKER_IMAGE_NAME}-migration:latest|image: ${env.DOCKER_IMAGE_NAME}-migration:${env.DOCKER_IMAGE_TAG}|g' kubernetes/12-migration-job.yaml
            
            # Add and commit changes
            git add kubernetes/08-easyshop-deployment.yaml kubernetes/12-migration-job.yaml
            git commit -m "Update image tags to ${env.DOCKER_IMAGE_TAG} [skip ci]" || echo "No changes to commit"
            
            # Try to push changes, but don't fail if it doesn't work
            git push https://\${GIT_USERNAME}:\${GIT_PASSWORD}@github.com/iemafzalhassan/easyshop.git update-image-tags-${env.BUILD_NUMBER} || echo "Failed to push changes, but continuing"
        """
    }
}