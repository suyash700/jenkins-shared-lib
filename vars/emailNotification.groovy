#!/usr/bin/env groovy

def call(String buildStatus) {
    def subject = "${buildStatus}: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]'"
    def color = buildStatus == 'SUCCESS' ? 'green' : 'red'
    def body = """
        <html>
        <body style="font-family: Arial, sans-serif;">
            <h2 style="color: ${color};">Build Status: ${buildStatus}</h2>
            <h3>Build Details:</h3>
            <ul>
                <li>Job: ${env.JOB_NAME}</li>
                <li>Build Number: ${env.BUILD_NUMBER}</li>
                <li>Build URL: <a href="${env.BUILD_URL}">${env.BUILD_URL}</a></li>
                <li>Git Branch: ${env.GIT_BRANCH}</li>
                <li>Git Commit: ${env.GIT_COMMIT}</li>
            </ul>
            
            <h3>Docker Images:</h3>
            <ul>
                <li>Image: ${env.DOCKER_IMAGE_NAME}:${env.DOCKER_IMAGE_TAG}</li>
                <li>Latest Tag: ${env.DOCKER_IMAGE_NAME}:latest</li>
            </ul>
            
            ${buildStatus == 'SUCCESS' 
                ? '<p style="color: green;">The application has been successfully deployed!</p>' 
                : '<p style="color: red;">The build has failed. Please check the logs for details.</p>'}
        </body>
        </html>
    """
    
    try {
        emailext (
            to: env.NOTIFICATION_EMAIL,
            subject: subject,
            body: body,
            mimeType: 'text/html',
            attachLog: true
        )
        echo "Email notification sent successfully"
    } catch (Exception e) {
        echo "Failed to send email notification: ${e.message}"
    }
}
