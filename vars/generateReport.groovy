#!/usr/bin/env groovy

/**
 * Generate build report
 *
 * @param projectName The name of the project
 * @param imageName The name of the Docker image
 * @param imageTag The tag for the Docker image
 */
def call(Map config = [:]) {
    def projectName = config.projectName ?: 'Project'
    def imageName = config.imageName ?: ''
    def imageTag = config.imageTag ?: ''
    
    echo "Generating build report..."
    
    // Create directory for reports
    sh "mkdir -p reports"
    
    // Generate report
    sh """
        echo "===== ${projectName} Build Report =====" > reports/build-report.txt
        echo "Generated: \$(date)" >> reports/build-report.txt
        echo "" >> reports/build-report.txt
        echo "Build Number: ${env.BUILD_NUMBER}" >> reports/build-report.txt
        echo "Docker Image: ${imageName}:${imageTag}" >> reports/build-report.txt
        echo "Build Status: ${currentBuild.result ?: 'SUCCESS'}" >> reports/build-report.txt
    """
    
    // Archive the report
    archiveArtifacts artifacts: 'reports/*', allowEmptyArchive: true
}