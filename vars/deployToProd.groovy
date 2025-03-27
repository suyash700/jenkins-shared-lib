#!/usr/bin/env groovy

def call() {
    echo "Starting production deployment"
    
    try {
        // Pull latest image
        echo "Pulling latest image"
        sh """
            docker pull ${env.DOCKER_IMAGE_NAME}:${env.DOCKER_IMAGE_TAG}
        """
        
        // Stop and remove existing containers
        echo "Stopping existing containers"
        sh """
            docker ps -q --filter "name=easyshop-prod" | grep -q . && docker stop easyshop-prod && docker rm easyshop-prod || echo "No production container running"
        """
        
        // Run new container
        echo "Starting new production container"
        sh """
            docker run -d \\
                --name easyshop-prod \\
                -p 3000:3000 \\
                -e NODE_ENV=production \\
                ${env.DOCKER_IMAGE_NAME}:${env.DOCKER_IMAGE_TAG}
        """
        
        echo "Production deployment completed successfully"
        echo "Application is accessible at: http://localhost:3000"
        
    } catch (Exception e) {
        echo "Error during production deployment: ${e.message}"
        throw e
    }
}
