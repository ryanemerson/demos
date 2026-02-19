#!/bin/bash
SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )

function terminal-title() {
    printf "\033]2;$1\007"
}

export KUBECONFIG=$HOME/.kube/config-jgroups-east
terminal-title East
minikube update-context
kubectl config set-context --current --namespace jgroups-east
kubectl apply -f https://skupper.io/install.yaml
kubectl rollout status deployment/skupper-controller -n skupper

skupper site create east
skupper site status
skupper token redeem west.token
skupper link status

cat <<EOF | kubectl apply -f -
apiVersion: v1
kind: Service
metadata:
  name: east-svc
spec:
  clusterIP: None
  publishNotReadyAddresses: true
  selector:
    app: app-east
  ports:
  - name: jgroups
    port: 7800
---
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: app-east
spec:
  serviceName: "east-svc"
  replicas: 2
  selector:
    matchLabels:
      app: app-east
  template:
    metadata:
      labels:
        app: app-east
    spec:
      containers:
      - name: quarkus
        image: quarkus-jgroups-demo:latest
        imagePullPolicy: Never
        env:
        - name: jgroups.bind_addr
          valueFrom:
            fieldRef:
              fieldPath: status.podIP
        ports:
        - containerPort: 7800
---
apiVersion: skupper.io/v2alpha1
kind: Listener
metadata:
  name: gossip-router
spec:
  host: gossip-router   # Creates the "gossip-router" DNS record locally
  port: 12001
  routingKey: gossip-router
## Skupper Connectors (Expose East pods to the WAN)
#apiVersion: skupper.io/v2alpha1
#kind: Connector
#metadata:
#  name: app-east-0
#spec:
#  port: 7800
#  routingKey: app-east-0
#  selector: "statefulset.kubernetes.io/pod-name=app-east-0"
#---
#apiVersion: skupper.io/v2alpha1
#kind: Connector
#metadata:
#  name: app-east-1
#spec:
#  port: 7800
#  routingKey: app-east-1
#  selector: "statefulset.kubernetes.io/pod-name=app-east-1"
#---
## Skupper Listeners (Bring West pods into local East DNS)
#apiVersion: skupper.io/v2alpha1
#kind: Listener
#metadata:
#  name: app-west-0
#spec:
#  host: app-west-0
#  port: 7800
#  routingKey: app-west-0
#---
#apiVersion: skupper.io/v2alpha1
#kind: Listener
#metadata:
#  name: app-west-1
#spec:
#  host: app-west-1
#  port: 7800
#  routingKey: app-west-1
EOF
kubectl rollout status statefulset/app-east
