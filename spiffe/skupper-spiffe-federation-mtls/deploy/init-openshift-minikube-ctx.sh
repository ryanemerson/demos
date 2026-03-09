#!/bin/bash
set -e
SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )

export KUBECONFIG=$HOME/.kube/public
CLUSTER_NAME=ryan $KCB/provision/aws/rosa_oc_login.sh

export KUBECONFIG=$HOME/.kube/private
minikube -p private update-context
