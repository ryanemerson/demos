#!/bin/bash
# Sets up the site-east Skupper site, deploys the hello-server, and creates
# the Skupper Connector that exposes hello-server:8443 to the Skupper network.
#set -e
SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )

function terminal-title() {
    printf "\033]2;$1\007"
}

export KUBECONFIG=$HOME/.kube/config-spiffe-east
terminal-title East
minikube update-context
kubectl config set-context --current --namespace site-east
kubectl apply -f https://skupper.io/install.yaml
kubectl rollout status deployment/skupper-controller -n skupper

skupper site create east
skupper site status

# Link to west using the token issued by init-west-client.sh
skupper token redeem "${SCRIPT_DIR}/west.token"
skupper link status

# ServiceAccount for hello-server workload attestation by east SPIRE Agent
kubectl apply -f - <<EOF
apiVersion: v1
kind: ServiceAccount
metadata:
  name: hello-server
  namespace: site-east
EOF

# Deploy hello-server
cat <<EOF | kubectl apply -f -
apiVersion: apps/v1
kind: Deployment
metadata:
  name: hello-server
  namespace: site-east
spec:
  replicas: 1
  selector:
    matchLabels:
      app: hello-server
  template:
    metadata:
      labels:
        app: hello-server
    spec:
      serviceAccountName: hello-server
      initContainers:
      # Wait for the SPIRE Agent socket before attempting to fetch an SVID.
      # busybox is used here because the spire-agent image is distroless (no shell).
      - name: wait-for-spire
        image: busybox:1.36
        command: ["sh", "-c"]
        args:
          - |
            until [ -S /run/spire/sockets/agent.sock ]; do
              echo "Waiting for east SPIRE agent socket..."
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
      - name: hello-server
        image: quarkus-hello-server:latest
        imagePullPolicy: Never
        ports:
        - containerPort: 8443
        env:
        # East SPIRE Agent socket (distinct hostPath from west to avoid conflicts)
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
          path: /run/spire/east
          type: DirectoryOrCreate
      - name: spiffe-certs
        emptyDir: {}
---
# Skupper Connector: exposes hello-server pods on port 8443 to the Skupper
# network using the routing key "hello-server". The west Listener maps to
# this key, completing the cross-site TCP tunnel.
apiVersion: skupper.io/v2alpha1
kind: Connector
metadata:
  name: hello-server
  namespace: site-east
spec:
  port: 8443
  routingKey: hello-server
  selector: "app=hello-server"
EOF

kubectl rollout status deployment/hello-server -n site-east
echo "site-east setup complete."
echo ""
echo "Verify mTLS is working:"
echo "  kubectl logs -n site-west deployment/hello-client -f"
