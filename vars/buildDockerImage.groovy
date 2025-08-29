def call(Map config = [:]) {
    def imageName = config.imageName ?: error("Image name is required")
    def imageTag = config.imageTag ?: 'latest'
    def dockerfile = config.dockerfile ?: 'Dockerfile'
    def context = config.context ?: '.'
    
    echo "Building Docker image: ${imageName}:${imageTag} using ${dockerfile}"
    
    // Run docker build with detailed logs
    def result = sh(
        script: """
            set -x
            docker build --progress=plain -t ${imageName}:${imageTag} -t ${imageName}:latest -f ${dockerfile} ${context}
        """,
        returnStatus: true
    )
    
    if (result != 0) {
        error("Docker build failed for ${imageName}:${imageTag} with exit code ${result}")
    }
    
    echo "Docker build completed successfully for ${imageName}:${imageTag}"
}
