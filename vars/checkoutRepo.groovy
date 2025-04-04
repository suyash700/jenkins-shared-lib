#!/usr/bin/env groovy

/**
 * Checkout the repository
 */
def call() {
    echo "Checking out repository..."
    checkout scm
}