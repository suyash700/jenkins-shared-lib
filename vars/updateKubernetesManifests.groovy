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
            
            # Fetch the latest changes from remote
            git fetch origin main
            
            # Create a new branch based on the latest main
            git checkout -b update-image-tags-${env.BUILD_NUMBER} origin/main
            
            # Apply our changes
            sed -i 's|image: ${env.DOCKER_IMAGE_NAME}:latest|image: ${env.DOCKER_IMAGE_NAME}:${env.DOCKER_IMAGE_TAG}|g' kubernetes/08-easyshop-deployment.yaml
            sed -i 's|image: ${env.DOCKER_IMAGE_NAME}-migration:latest|image: ${env.DOCKER_IMAGE_NAME}-migration:${env.DOCKER_IMAGE_TAG}|g' kubernetes/12-migration-job.yaml
            
            # Add and commit changes
            git add kubernetes/08-easyshop-deployment.yaml kubernetes/12-migration-job.yaml
            git commit -m "Update image tags to ${env.DOCKER_IMAGE_TAG} [skip ci]" || echo "No changes to commit"
            
            # Push to the new branch
            git push -f https://\${GIT_USERNAME}:\${GIT_PASSWORD}@github.com/iemafzalhassan/easyshop.git update-image-tags-${env.BUILD_NUMBER}
            
            # Create a pull request (optional, requires GitHub CLI or API call)
            # For now, we'll just push to the branch and you can merge manually
            echo "Changes pushed to branch update-image-tags-${env.BUILD_NUMBER}"
            echo "Please create a pull request to merge these changes into main"
        """
    }
}