def call(Map config = [:]) {
    def imageName = config.imageName ?: 'default-image'
    def imageTag = config.imageTag ?: 'latest'
    def dockerfile = config.dockerfile ?: 'Dockerfile'
    def context = config.context ?: '.'
    
    echo "Building Docker image: ${imageName}:${imageTag} using ${dockerfile}"
    
    // Run docker build with detailed logs
    sh """
        set -x
        docker build -t ${imageName}:${imageTag} -t ${imageName}:latest -f ${dockerfile} ${context}
    """
    
    echo "Docker build completed for ${imageName}:${imageTag}"
}
