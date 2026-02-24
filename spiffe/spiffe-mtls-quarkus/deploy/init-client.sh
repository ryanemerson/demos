#!/bin/bash
#set -e
SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )

kubectl create namespace client || true

# ServiceAccount for hello-client workload attestation
kubectl apply -f - <<EOF
apiVersion: v1
kind: ServiceAccount
metadata:
  name: hello-client
  namespace: client
EOF

# Deploy hello-client
cat <<EOF | kubectl apply -f -
apiVersion: apps/v1
kind: Deployment
metadata:
  name: hello-client
  namespace: client
spec:
  replicas: 1
  selector:
    matchLabels:
      app: hello-client
  template:
    metadata:
      labels:
        app: hello-client
    spec:
      serviceAccountName: hello-client
      containers:
      - name: hello-client
        image: quarkus-hello-client:latest
        imagePullPolicy: Never
        env:
        - name: SPIFFE_ENDPOINT_SOCKET
          value: "unix:/run/spire/sockets/agent.sock"
        - name: SPIFFE_IDS
          value: "spiffe://demo.example.com/hello-server"
        - name: SPIFFE_TLS
          value: "mtls"
        - name: JAVA_OPTS_APPEND
          value: "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005 -Djavax.net.debug=ssl:handshake"
        volumeMounts:
        - name: spire-agent-socket
          mountPath: /run/spire/sockets
      volumes:
      - name: spire-agent-socket
        hostPath:
          path: /run/spire/socket
          type: DirectoryOrCreate
EOF

kubectl rollout status deployment/hello-client -n client
echo "Client setup complete."
