def call(Map config = [:]) {
    def imageName = config.imageName ?: error("Image name is required")
    def imageTag = config.imageTag ?: 'latest'
    def dockerfile = config.dockerfile ?: 'Dockerfile'
    def context = config.context ?: '.'
    
    echo "Building Docker image: ${imageName}:${imageTag} using ${dockerfile}"
    
    // Run docker build with detailed logs   
        sh docker build  -t ${imageName}:${imageTag} -t ${imageName}:latest -f ${dockerfile} ${context}
       
    
    echo "Docker build completed successfully for ${imageName}:${imageTag}"
}
