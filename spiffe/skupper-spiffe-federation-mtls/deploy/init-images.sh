#!/bin/bash
set -e
SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )

PUBLIC_PLATFORM="${PUBLIC_PLATFORM:-minikube}"
PRIVATE_PLATFORM="${PRIVATE_PLATFORM:-minikube}"

CLIENT_IMAGE="quay.io/remerson/hello-client:latest"
SERVER_IMAGE="quay.io/remerson/hello-server:latest"

echo "Building quarkus-hello-server..."
cd "${SCRIPT_DIR}/../server"
mvn clean package -DskipTests -q

if [ "${PRIVATE_PLATFORM}" = "openshift" ]; then
  docker buildx build --platform linux/amd64,linux/arm64 -t "${SERVER_IMAGE}" --push .
else
  # Build directly inside minikube so images are available without a registry
  eval $(minikube -p private docker-env)
  export DOCKER_API_VERSION=1.44
  docker build -t "${SERVER_IMAGE}" .
fi

# Reset Docker environment so subsequent builds use the local daemon
unset DOCKER_HOST DOCKER_TLS_VERIFY DOCKER_CERT_PATH DOCKER_API_VERSION

echo "Building quarkus-hello-client..."
cd "${SCRIPT_DIR}/../client"
mvn clean package -DskipTests -q

if [ "${PUBLIC_PLATFORM}" = "openshift" ]; then
  docker buildx build --platform linux/amd64,linux/arm64 -t "${CLIENT_IMAGE}" --push .
else
  eval $(minikube -p public docker-env)
  export DOCKER_API_VERSION=1.44
  docker build -t "${CLIENT_IMAGE}" .
fi

echo "Images built successfully."
