#!/bin/bash
SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )

export KUBECONFIG=$HOME/.kube/config-jgroups-west
skupper site delete --all
kubectl delete statefulset/app-west
kubectl delete service/west-svc

export KUBECONFIG=$HOME/.kube/config-jgroups-east
skupper site delete --all
kubectl delete statefulset/app-east
kubectl delete service/east-svc
