#!/bin/bash
# Sets up the site-west Skupper site, deploys the hello-client, and creates
# the Skupper Listener that maps hello-server:8443 through the Skupper network
# to the hello-server running in site-east.
#set -e
SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )

function terminal-title() {
    printf "\033]2;$1\007"
}

export KUBECONFIG=$HOME/.kube/config-spiffe-west
terminal-title West
minikube update-context
kubectl config set-context --current --namespace site-west
kubectl apply -f https://skupper.io/install.yaml
kubectl rollout status deployment/skupper-controller -n skupper

skupper site create west --enable-link-access
skupper site status

# Issue a token that the east site will use to link back to west
skupper token issue "${SCRIPT_DIR}/west.token"

# ServiceAccount for hello-client workload attestation by west SPIRE Agent
kubectl apply -f - <<EOF
apiVersion: v1
kind: ServiceAccount
metadata:
  name: hello-client
  namespace: site-west
EOF

# Deploy hello-client
cat <<EOF | kubectl apply -f -
apiVersion: apps/v1
kind: Deployment
metadata:
  name: hello-client
  namespace: site-west
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
              echo "Waiting for west SPIRE agent socket..."
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
      # spire-agent writes: svid.0.pem, svid.0.key, bundle.0.pem (own CA),
      # federated_bundle.0.0.pem (peer CA). All need to be readable by UID 185.
      - name: fix-perms
        image: busybox:1.36
        command: ["sh", "-c"]
        args:
          - chmod 644 /opt/spiffe-certs/svid.0.pem /opt/spiffe-certs/svid.0.key /opt/spiffe-certs/bundle.0.pem /opt/spiffe-certs/federated_bundle.0.0.pem
        volumeMounts:
        - name: spiffe-certs
          mountPath: /opt/spiffe-certs
      containers:
      - name: hello-client
        image: quarkus-hello-client:latest
        imagePullPolicy: Never
        env:
        # West SPIRE Agent socket (distinct hostPath from east to avoid conflicts)
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
          path: /run/spire/west
          type: DirectoryOrCreate
      - name: spiffe-certs
        emptyDir: {}
---
# Skupper Listener: creates a local hello-server:8443 DNS entry in site-west.
# Traffic is forwarded through the Skupper network to the Connector in site-east.
apiVersion: skupper.io/v2alpha1
kind: Listener
metadata:
  name: hello-server
  namespace: site-west
spec:
  host: hello-server
  port: 8443
  routingKey: hello-server
EOF

kubectl rollout status deployment/hello-client -n site-west
echo "site-west setup complete."
echo "Token issued at ${SCRIPT_DIR}/west.token — run init-east-client.sh to link."
