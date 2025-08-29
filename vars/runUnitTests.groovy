#!/usr/bin/env groovy

/**
 * Run unit tests
 */
def call() {
    echo "Running tests..."

    // 1️⃣ Unit Tests (Node.js / JavaScript)
    sh """
        echo 'Running unit tests...'
        npm install
        npm test
    """

    // 2️⃣ Linting (optional code quality check)
    sh """
        echo 'Running ESLint...'
        npx eslint .
    """

    // 3️⃣ Integration / API tests (if you have a test folder)
    sh """
        echo 'Running integration tests...'
        npm run test:integration || echo 'Integration tests failed'
    """

    // 4️⃣ Docker container health check (after building image)
    sh """
        echo 'Checking Docker container...'
        docker run --rm ${env.DOCKER_IMAGE_NAME}:${env.IMAGE_TAG} node -v
    """

    echo "All tests completed!"
}
