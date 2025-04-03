def call(Map config = [:]) {
    def imageName = config.imageName ?: 'iemafzal/easyshop'
    def imageTag = config.imageTag ?: 'latest'
    def dockerHubCredentialsId = config.dockerHubCredentialsId ?: 'docker-hub-credentials'
    def migrationDockerfilePath = config.migrationDockerfilePath ?: 'scripts/Dockerfile.migration'
    
    withCredentials([usernamePassword(credentialsId: dockerHubCredentialsId, passwordVariable: 'DOCKER_PASSWORD', usernameVariable: 'DOCKER_USERNAME')]) {
        sh """
            echo "\$DOCKER_PASSWORD" | docker login -u "\$DOCKER_USERNAME" --password-stdin
            
            # Build and push main application
            echo "Building main application image: ${imageName}:${imageTag}..."
            docker build -t ${imageName}:${imageTag} -t ${imageName}:latest .
            
            echo "Pushing main application image: ${imageName}:${imageTag}..."
            docker push ${imageName}:${imageTag}
            docker push ${imageName}:latest
            
            # Build and push migration job image
            echo "Building migration image: ${imageName}-migration:${imageTag}..."
            docker build -t ${imageName}-migration:${imageTag} -t ${imageName}-migration:latest -f ${migrationDockerfilePath} .
            
            echo "Pushing migration image: ${imageName}-migration:${imageTag}..."
            docker push ${imageName}-migration:${imageTag}
            docker push ${imageName}-migration:latest
            
            echo "All Docker images built and pushed successfully."
        """
    }
}