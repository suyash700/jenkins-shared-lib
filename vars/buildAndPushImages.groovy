#!/usr/bin/env groovy

def call() {
    echo "Building and pushing Docker images..."
    
    // Login to Docker Hub
    sh '''
        echo "${DOCKER_HUB_CREDENTIALS_PSW}" | docker login -u "${DOCKER_HUB_CREDENTIALS_USR}" --password-stdin
    '''
    
    // Build and push main application image
    sh '''
        echo "Building main application image..."
        docker build -t ${DOCKER_IMAGE_NAME}:${DOCKER_IMAGE_TAG} -f Dockerfile .
        
        echo "Pushing main application image..."
        docker push ${DOCKER_IMAGE_NAME}:${DOCKER_IMAGE_TAG}
        
        # Tag as latest
        docker tag ${DOCKER_IMAGE_NAME}:${DOCKER_IMAGE_TAG} ${DOCKER_IMAGE_NAME}:latest
        docker push ${DOCKER_IMAGE_NAME}:latest
    '''
    
    // Build and push migration image
    sh '''
        echo "Building migration image..."
        docker build -t ${DOCKER_IMAGE_NAME}-migration:${DOCKER_IMAGE_TAG} -f Dockerfile.migration .
        
        echo "Pushing migration image..."
        docker push ${DOCKER_IMAGE_NAME}-migration:${DOCKER_IMAGE_TAG}
        
        # Tag as latest
        docker tag ${DOCKER_IMAGE_NAME}-migration:${DOCKER_IMAGE_TAG} ${DOCKER_IMAGE_NAME}-migration:latest
        docker push ${DOCKER_IMAGE_NAME}-migration:latest
    '''
    
    // Update Kubernetes manifests with new image tags
    sh '''
        chmod +x scripts/update-k8s-manifests.sh
        ./scripts/update-k8s-manifests.sh ${DOCKER_IMAGE_NAME} ${DOCKER_IMAGE_TAG} ${GITHUB_CREDENTIALS_USR} ${GITHUB_CREDENTIALS_PSW}
    '''
    
    echo "Docker images built and pushed successfully!"
}