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
                --build-arg MONGODB_URI="mongodb://mongodb-service.easyshop.svc.cluster.local:27017/easyshop" \\
                --build-arg REDIS_URI="redis://easyshop-redis.easyshop.svc.cluster.local:6379" \\
                --build-arg NEXTAUTH_URL="https://easyshop.iemafzalhassan.tech" \\
                --build-arg NEXT_PUBLIC_API_URL="https://easyshop.iemafzalhassan.tech/api" \\
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
        sh """
            docker build -t ${env.DOCKER_IMAGE_NAME}-migration:${env.DOCKER_IMAGE_TAG} \\
                -t ${env.DOCKER_IMAGE_NAME}-migration:latest \\
                -f ${migrationDockerfile} .
        """
        echo "Successfully built Docker image: ${env.DOCKER_IMAGE_NAME}-migration:${env.DOCKER_IMAGE_TAG}"
    } catch (Exception e) {
        echo "Error building Docker image: ${e.message}"
        throw e
    }
}
