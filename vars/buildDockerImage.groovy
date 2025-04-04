#!/usr/bin/env groovy

/**
 * Build Docker image
 *
 * @param imageName The name of the Docker image
 * @param imageTag The tag for the Docker image
 */
def call(Map config = [:]) {
    def imageName = config.imageName ?: error("Image name is required")
    def imageTag = config.imageTag ?: 'latest'
    
    echo "Building Docker image: ${imageName}:${imageTag}"
    
    sh """
        docker build -t ${imageName}:${imageTag} -t ${imageName}:latest .
    """
}