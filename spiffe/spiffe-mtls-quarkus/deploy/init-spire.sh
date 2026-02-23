#!/bin/bash
set -e

SPIRE_VERSION="1.9.4"
SPIRE_SERVER_IMAGE="ghcr.io/spiffe/spire-server:${SPIRE_VERSION}"
SPIRE_AGENT_IMAGE="ghcr.io/spiffe/spire-agent:${SPIRE_VERSION}"

kubectl create namespace spire || true

# SPIRE Servers validate agent tokens via the Kubernetes TokenReview API and
# query pod/node metadata to verify k8s_psat attestation claims.
kubectl apply -f - <<EOF
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: spire-server-cluster-role
rules:
- apiGroups: ["authentication.k8s.io"]
  resources: ["tokenreviews"]
  verbs: ["create"]
- apiGroups: [""]
  resources: ["nodes", "pods"]
  verbs: ["get", "list", "watch"]
EOF

# SPIRE Agents need to list nodes and pods for workload attestation.
kubectl apply -f - <<EOF
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: spire-agent-cluster-role
rules:
- apiGroups: [""]
  resources: ["nodes", "pods"]
  verbs: ["get", "list", "watch"]
- apiGroups: [""]
  resources: ["nodes/proxy"]
  verbs: ["get"]
EOF

echo "--- Deploying SPIRE ---"

kubectl apply -f - <<EOF
apiVersion: v1
kind: ServiceAccount
metadata:
  name: spire-server
  namespace: spire
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: spire-server-cluster-role-binding
subjects:
- kind: ServiceAccount
  name: spire-server
  namespace: spire
roleRef:
  kind: ClusterRole
  name: spire-server-cluster-role
  apiGroup: rbac.authorization.k8s.io
---
apiVersion: v1
kind: ServiceAccount
metadata:
  name: spire-agent
  namespace: spire
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: spire-agent-cluster-role-binding
subjects:
- kind: ServiceAccount
  name: spire-agent
  namespace: spire
roleRef:
  kind: ClusterRole
  name: spire-agent-cluster-role
  apiGroup: rbac.authorization.k8s.io
EOF

kubectl apply -f - <<'EOF'
apiVersion: v1
kind: ConfigMap
metadata:
  name: spire-server-config
  namespace: spire
data:
  server.conf: |
    server {
      bind_address = "0.0.0.0"
      bind_port = "8081"
      socket_path = "/tmp/spire-server/private/api.sock"
      trust_domain = "demo.example.com"
      data_dir = "/run/spire/data"
      log_level = "INFO"
      ca_ttl = "12h"
      default_x509_svid_ttl = "1h"
    }
    plugins {
      DataStore "sql" {
        plugin_data {
          database_type = "sqlite3"
          connection_string = "/run/spire/data/datastore.sqlite3"
        }
      }
      NodeAttestor "k8s_psat" {
        plugin_data {
          clusters = {
            "minikube" = {
              service_account_allow_list = ["spire:spire-agent"]
              audience = ["spire-server"]
            }
          }
        }
      }
      KeyManager "disk" {
        plugin_data {
          keys_path = "/run/spire/data/keys.json"
        }
      }
    }
EOF

kubectl apply -f - <<'EOF'
apiVersion: v1
kind: ConfigMap
metadata:
  name: spire-agent-config
  namespace: spire
data:
  agent.conf: |
    agent {
      data_dir = "/run/spire/agent"
      log_level = "INFO"
      server_address = "spire-server.spire.svc.cluster.local"
      server_port = "8081"
      socket_path = "/run/spire/sockets/agent.sock"
      trust_domain = "demo.example.com"
      insecure_bootstrap = true
    }
    plugins {
      NodeAttestor "k8s_psat" {
        plugin_data {
          cluster = "minikube"
          token_path = "/var/run/secrets/tokens/spire-agent"
        }
      }
      KeyManager "memory" {
        plugin_data {}
      }
      WorkloadAttestor "k8s" {
        plugin_data {
          skip_kubelet_verification = true
          node_name_env = "MY_NODE_NAME"
        }
      }
    }
EOF

kubectl apply -f - <<EOF
apiVersion: v1
kind: Service
metadata:
  name: spire-server
  namespace: spire
spec:
  selector:
    app: spire-server
  ports:
  - name: grpc
    port: 8081
    targetPort: 8081
---
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: spire-server
  namespace: spire
spec:
  replicas: 1
  selector:
    matchLabels:
      app: spire-server
  serviceName: spire-server
  template:
    metadata:
      labels:
        app: spire-server
    spec:
      serviceAccountName: spire-server
      containers:
      - name: spire-server
        image: ${SPIRE_SERVER_IMAGE}
        args: ["-config", "/run/spire/config/server.conf"]
        ports:
        - containerPort: 8081
        volumeMounts:
        - name: spire-server-config
          mountPath: /run/spire/config
          readOnly: true
        - name: spire-data
          mountPath: /run/spire/data
        - name: spire-server-socket
          mountPath: /tmp/spire-server/private
        readinessProbe:
          exec:
            command:
            - /opt/spire/bin/spire-server
            - healthcheck
            - -socketPath
            - /tmp/spire-server/private/api.sock
          initialDelaySeconds: 5
          periodSeconds: 5
      volumes:
      - name: spire-server-config
        configMap:
          name: spire-server-config
      - name: spire-data
        emptyDir: {}
      - name: spire-server-socket
        emptyDir: {}
EOF

kubectl apply -f - <<EOF
apiVersion: apps/v1
kind: DaemonSet
metadata:
  name: spire-agent
  namespace: spire
spec:
  selector:
    matchLabels:
      app: spire-agent
  template:
    metadata:
      labels:
        app: spire-agent
    spec:
      hostPID: true
      hostNetwork: true
      dnsPolicy: ClusterFirstWithHostNet
      serviceAccountName: spire-agent
      containers:
      - name: spire-agent
        image: ${SPIRE_AGENT_IMAGE}
        args: ["-config", "/run/spire/config/agent.conf"]
        env:
        - name: MY_NODE_NAME
          valueFrom:
            fieldRef:
              fieldPath: spec.nodeName
        volumeMounts:
        - name: spire-agent-config
          mountPath: /run/spire/config
          readOnly: true
        - name: spire-agent-socket-dir
          mountPath: /run/spire/sockets
        - name: spire-agent-data
          mountPath: /run/spire/agent
        - name: spire-agent-token
          mountPath: /var/run/secrets/tokens
          readOnly: true
        readinessProbe:
          exec:
            command:
            - /opt/spire/bin/spire-agent
            - healthcheck
            - -socketPath
            - /run/spire/sockets/agent.sock
          initialDelaySeconds: 10
          periodSeconds: 5
      volumes:
      - name: spire-agent-config
        configMap:
          name: spire-agent-config
      - name: spire-agent-socket-dir
        hostPath:
          # Unique per-namespace path prevents socket conflicts on the shared minikube node
          path: /run/spire/socket
          type: DirectoryOrCreate
      - name: spire-agent-data
        emptyDir: {}
      - name: spire-agent-token
        projected:
          sources:
          - serviceAccountToken:
              path: spire-agent
              expirationSeconds: 7200
              audience: spire-server
EOF

# ---------------------------------------------------------------------------
# Wait for SPIRE servers and agents to be ready
# ---------------------------------------------------------------------------
echo "Waiting for SPIRE servers to be ready..."
kubectl rollout status statefulset/spire-server -n spire

echo "Waiting for SPIRE agents to be ready..."
kubectl rollout status daemonset/spire-agent -n spire

# ---------------------------------------------------------------------------
# Register workload entries
# ---------------------------------------------------------------------------
echo "--- Registering workload entries ---"

kubectl exec -n spire spire-server-0 -- \
    /opt/spire/bin/spire-server entry create \
    -spiffeID spiffe://demo.example.com/ns/spire/sa/spire-agent \
    -selector k8s_psat:cluster:minikube \
    -selector k8s_psat:agent_ns:spire \
    -selector k8s_psat:agent_sa:spire-agent \
    -node


# hello-server workload.
kubectl exec -n spire statefulset/spire-server -c spire-server -- \
  /opt/spire/bin/spire-server entry create \
  -spiffeID spiffe://demo.example.com/hello-server \
  -parentID spiffe://demo.example.com/ns/spire/sa/spire-agent \
  -selector k8s:ns:server \
  -selector k8s:sa:hello-server

# hello-client workload.
kubectl exec -n spire statefulset/spire-server -c spire-server -- \
  /opt/spire/bin/spire-server entry create \
  -spiffeID spiffe://demo.example.com/hello-client \
  -parentID spiffe://demo.example.com/ns/spire/sa/spire-agent \
  -selector k8s:ns:client \
  -selector k8s:sa:hello-client

echo ""
echo "SPIRE deployed and federated successfully."
echo ""
echo "Trust domains:"
echo "  demo.example.com/hello-client"
echo "  demo.example.com/hello-server"
echo ""
echo "SPIFFE IDs:"
echo "  Server: spiffe://demo.example.com/hello-server"
echo "  Client: spiffe://demo.example.com/hello-client"
echo ""
echo "Next: run init-server.sh then init-client.sh"
