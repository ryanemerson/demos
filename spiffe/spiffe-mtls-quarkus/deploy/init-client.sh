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
      initContainers:
      # Wait for the SPIRE Agent socket before attempting to fetch an SVID.
      # busybox is used here because the spire-agent image is distroless (no shell).
      - name: wait-for-spire
        image: busybox:1.36
        command: ["sh", "-c"]
        args:
          - |
            until [ -S /run/spire/sockets/agent.sock ]; do
              echo "Waiting for SPIRE agent socket..."
              sleep 2
            done
        volumeMounts:
        - name: spire-agent-socket
          mountPath: /run/spire/sockets
      # Fetch the X.509 SVID directly using the spire-agent binary (no shell needed).
      # Writes: svid.0.pem (cert chain), svid.0.key (private key), bundle.0.pem (trust bundle).
      - name: spiffe-init
        image: ghcr.io/spiffe/spire-agent:1.9.4
        command:
          - /opt/spire/bin/spire-agent
          - api
          - fetch
          - x509
          - -socketPath
          - /run/spire/sockets/agent.sock
          - -write
          - /opt/spiffe-certs/
        volumeMounts:
        - name: spire-agent-socket
          mountPath: /run/spire/sockets
        - name: spiffe-certs
          mountPath: /opt/spiffe-certs
      # spire-agent writes files as root with mode 0600; the Quarkus app runs as
      # UID 185 (ubi9/openjdk-17 default) and cannot read them without this step.
      # spire-agent writes: svid.0.pem, svid.0.key, bundle.0.pem (own CA). All need to be readable by UID 185.
      - name: fix-perms
        image: busybox:1.36
        command: ["sh", "-c"]
        args:
          - chmod 644 /opt/spiffe-certs/svid.0.pem /opt/spiffe-certs/svid.0.key /opt/spiffe-certs/bundle.0.pem
        volumeMounts:
        - name: spiffe-certs
          mountPath: /opt/spiffe-certs
      containers:
      - name: hello-client
        image: quarkus-hello-client:latest
        imagePullPolicy: Never
        env:
        - name: SPIRE_SOCKET_PATH
          value: "unix:/run/spire/sockets/agent.sock"
        volumeMounts:
        - name: spire-agent-socket
          mountPath: /run/spire/sockets
        - name: spiffe-certs
          mountPath: /opt/spiffe-certs
      volumes:
      - name: spire-agent-socket
        hostPath:
          path: /run/spire/socket
          type: DirectoryOrCreate
      - name: spiffe-certs
        emptyDir: {}
EOF

kubectl rollout status deployment/hello-client -n client
echo "Client setup complete."
