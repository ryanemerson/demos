#!/bin/bash
SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )

function terminal-title() {
    printf "\033]2;$1\007"
}

export KUBECONFIG=$HOME/.kube/config-jgroups-west
terminal-title West
minikube update-context
kubectl config set-context --current --namespace jgroups-west
kubectl apply -f https://skupper.io/install.yaml
kubectl rollout status deployment/skupper-controller -n skupper

skupper site create west --enable-link-access
skupper site status
skupper token issue west.token
skupper link status

cat <<EOF | kubectl apply -f -
apiVersion: v1
kind: Service
metadata:
  name: west-svc
spec:
  clusterIP: None
  publishNotReadyAddresses: true
  selector:
    app: app-west
  ports:
  - name: jgroups
    port: 7800
---
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: app-west
spec:
  serviceName: "west-svc"
  replicas: 2
  selector:
    matchLabels:
      app: app-west
  template:
    metadata:
      labels:
        app: app-west
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
apiVersion: apps/v1
kind: Deployment
metadata:
  name: gossiprouter
spec:
  replicas: 1
  selector:
    matchLabels:
      app: gossiprouter
  template:
    metadata:
      labels:
        app: gossiprouter
    spec:
      containers:
      - name: router
        image: eclipse-temurin:17-jre
        command: ["/bin/sh", "-c"]
        # Downloads the JGroups JAR and boots the Router on port 12001
        args:
          - wget -q https://repo1.maven.org/maven2/org/jgroups/jgroups/5.3.3.Final/jgroups-5.3.3.Final.jar && java -cp jgroups-5.3.3.Final.jar org.jgroups.stack.GossipRouter -port 12001
        ports:
        - containerPort: 12001
---
apiVersion: v1
kind: Service
metadata:
  name: gossip-router
spec:
  selector:
    app: gossiprouter
  ports:
  - port: 12001
    targetPort: 12001
---
apiVersion: skupper.io/v2alpha1
kind: Connector
metadata:
  name: gossip-router
spec:
  port: 12001
  routingKey: gossip-router
  selector: "app=gossiprouter"

## Skupper Connectors (Expose West pods to the WAN)
#apiVersion: skupper.io/v2alpha1
#kind: Connector
#metadata:
#  name: app-west-0
#spec:
#  port: 7800
#  routingKey: app-west-0
#  selector: "statefulset.kubernetes.io/pod-name=app-west-0"
#---
#apiVersion: skupper.io/v2alpha1
#kind: Connector
#metadata:
#  name: app-west-1
#spec:
#  port: 7800
#  routingKey: app-west-1
#  selector: "statefulset.kubernetes.io/pod-name=app-west-1"
#---
## Skupper Listeners (Bring East pods into local West DNS)
#apiVersion: skupper.io/v2alpha1
#kind: Listener
#metadata:
#  name: app-east-0
#spec:
#  host: app-east-0
#  port: 7800
#  routingKey: app-east-0
#---
#apiVersion: skupper.io/v2alpha1
#kind: Listener
#metadata:
#  name: app-east-1
#spec:
#  host: app-east-1
#  port: 7800
#  routingKey: app-east-1
EOF
kubectl rollout status statefulset/app-west
#kubectl port-forward statefulset/app-west 8080:8080
