#!/bin/bash
set -e
SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )

cd $SCRIPT_DIR/..
eval $(minikube -p minikube docker-env)
export DOCKER_API_VERSION=1.43
docker build -t quarkus-jgroups-demo .