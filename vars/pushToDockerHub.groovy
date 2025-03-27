#!/usr/bin/env groovy

def call() {
    echo "Preparing to push Docker images to Docker Hub"
    
    withCredentials([usernamePassword(
        credentialsId: 'docker-hub-credentials',
        usernameVariable: 'DOCKER_USER',
        passwordVariable: 'DOCKER_PASS'
    )]) {
        try {
            // Login to Docker Hub
            sh "echo \${DOCKER_PASS} | docker login -u \${DOCKER_USER} --password-stdin"
            echo "Successfully logged in to Docker Hub"
            
            // Push main application images
            echo "Pushing main application images"
            sh """
                docker push ${env.DOCKER_IMAGE_NAME}:${env.DOCKER_IMAGE_TAG}
                docker push ${env.DOCKER_IMAGE_NAME}:latest
            """
            echo "Successfully pushed main application images"
            
            // Push migration images
            echo "Pushing migration images"
            sh """
                docker push ${env.DOCKER_IMAGE_NAME}-migration:${env.DOCKER_IMAGE_TAG}
                docker push ${env.DOCKER_IMAGE_NAME}-migration:latest
            """
            echo "Successfully pushed migration images"
            
        } catch (Exception e) {
            echo "Error pushing Docker images: ${e.message}"
            throw e
        } finally {
            // Always try to logout
            sh "docker logout"
            echo "Logged out from Docker Hub"
        }
    }
}
