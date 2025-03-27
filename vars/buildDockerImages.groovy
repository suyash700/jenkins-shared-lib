#!/usr/bin/env groovy

def call() {
    def dockerfile = 'Dockerfile'
    def migrationDockerfile = 'scripts/Dockerfile.migration'
    
    // Build main application image
    echo "Building Docker image: ${env.DOCKER_IMAGE_NAME}:${env.DOCKER_IMAGE_TAG}"
    
    try {
        // Add build arguments for required environment variables
        sh """
            docker build -t ${env.DOCKER_IMAGE_NAME}:${env.DOCKER_IMAGE_TAG} \\
                -t ${env.DOCKER_IMAGE_NAME}:latest \\
                --build-arg MONGODB_URI="mongodb://mongodb:27017/easyshop" \\
                --build-arg REDIS_URI="redis://redis:6379" \\
                -f ${dockerfile} .
        """
        echo "Successfully built Docker image: ${env.DOCKER_IMAGE_NAME}:${env.DOCKER_IMAGE_TAG}"
    } catch (Exception e) {
        echo "Error building Docker image: ${e.message}"
        throw e
    }
    
    // Build migration image
    echo "Building Docker image: ${env.DOCKER_IMAGE_NAME}-migration:${env.DOCKER_IMAGE_TAG}"
    
    try {
        // Add the same build arguments to migration image
        sh """
            docker build -t ${env.DOCKER_IMAGE_NAME}-migration:${env.DOCKER_IMAGE_TAG} \\
                -t ${env.DOCKER_IMAGE_NAME}-migration:latest \\
                --build-arg MONGODB_URI="mongodb://mongodb:27017/easyshop" \\
                --build-arg REDIS_URI="redis://redis:6379" \\
                -f ${migrationDockerfile} .
        """
        echo "Successfully built Docker image: ${env.DOCKER_IMAGE_NAME}-migration:${env.DOCKER_IMAGE_TAG}"
    } catch (Exception e) {
        echo "Error building Docker image: ${e.message}"
        throw e
    }
}
