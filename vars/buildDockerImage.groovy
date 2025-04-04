#!/usr/bin/env groovy

/**
 * Build Docker image
 *
 * @param imageName The name of the Docker image
 * @param imageTag The tag for the Docker image
 * @param dockerfile The path to the Dockerfile (optional)
 * @param context The build context (optional)
 */
def call(Map config = [:]) {
    def imageName = config.imageName ?: error("Image name is required")
    def imageTag = config.imageTag ?: 'latest'
    def dockerfile = config.dockerfile ?: 'Dockerfile'
    def context = config.context ?: '.'
    
    echo "Building Docker image: ${imageName}:${imageTag} using ${dockerfile}"
    
    sh """
        docker build -t ${imageName}:${imageTag} -t ${imageName}:latest -f ${dockerfile} ${context}
    """
}