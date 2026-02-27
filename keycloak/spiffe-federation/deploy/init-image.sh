#!/bin/bash
set -e
SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )

# Build Docker images directly inside minikube so they are available
# without needing a registry. imagePullPolicy: Never is used in pod specs.
eval $(minikube -p minikube docker-env)
export DOCKER_API_VERSION=1.44

echo "Building quarkus-hello-server..."
cd "${SCRIPT_DIR}/../server"
mvn clean package -DskipTests -q
docker build -t quarkus-hello-server:latest .

echo "Building quarkus-hello-client..."
cd "${SCRIPT_DIR}/../client"
mvn clean package -DskipTests -q
docker build -t quarkus-hello-client:latest .

echo "Images built successfully."
