#!/bin/bash
# Deploys SPIRE in site-east and site-west with separate trust domains,
# then federates the two trust domains so the server and client can
# establish mutual TLS using cross-site SPIFFE identities.
#
# Trust domains:
#   site-east -> east.demo.example.com
#   site-west -> west.demo.example.com
#
# SPIFFE IDs:
#   Server: spiffe://east.demo.example.com/hello-server
#   Client: spiffe://west.demo.example.com/hello-client
set -e

SPIRE_VERSION="1.9.4"
SPIRE_SERVER_IMAGE="ghcr.io/spiffe/spire-server:${SPIRE_VERSION}"
SPIRE_AGENT_IMAGE="ghcr.io/spiffe/spire-agent:${SPIRE_VERSION}"

# ---------------------------------------------------------------------------
# Shared RBAC
# ---------------------------------------------------------------------------

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

# ---------------------------------------------------------------------------
# site-east SPIRE
# ---------------------------------------------------------------------------
echo "--- Deploying SPIRE in site-east ---"

kubectl apply -f - <<EOF
apiVersion: v1
kind: ServiceAccount
metadata:
  name: spire-server
  namespace: site-east
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: spire-server-east-cluster-role-binding
subjects:
- kind: ServiceAccount
  name: spire-server
  namespace: site-east
roleRef:
  kind: ClusterRole
  name: spire-server-cluster-role
  apiGroup: rbac.authorization.k8s.io
---
apiVersion: v1
kind: ServiceAccount
metadata:
  name: spire-agent
  namespace: site-east
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: spire-agent-east-cluster-role-binding
subjects:
- kind: ServiceAccount
  name: spire-agent
  namespace: site-east
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
  namespace: site-east
data:
  server.conf: |
    server {
      bind_address = "0.0.0.0"
      bind_port = "8081"
      socket_path = "/tmp/spire-server/private/api.sock"
      trust_domain = "east.demo.example.com"
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
              service_account_allow_list = ["site-east:spire-agent"]
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
  namespace: site-east
data:
  agent.conf: |
    agent {
      data_dir = "/run/spire/agent"
      log_level = "INFO"
      server_address = "spire-server.site-east.svc.cluster.local"
      server_port = "8081"
      socket_path = "/run/spire/sockets/agent.sock"
      trust_domain = "east.demo.example.com"
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
  namespace: site-east
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
  namespace: site-east
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
  namespace: site-east
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
          path: /run/spire/east
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
# site-west SPIRE
# ---------------------------------------------------------------------------
echo "--- Deploying SPIRE in site-west ---"

kubectl apply -f - <<EOF
apiVersion: v1
kind: ServiceAccount
metadata:
  name: spire-server
  namespace: site-west
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: spire-server-west-cluster-role-binding
subjects:
- kind: ServiceAccount
  name: spire-server
  namespace: site-west
roleRef:
  kind: ClusterRole
  name: spire-server-cluster-role
  apiGroup: rbac.authorization.k8s.io
---
apiVersion: v1
kind: ServiceAccount
metadata:
  name: spire-agent
  namespace: site-west
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: spire-agent-west-cluster-role-binding
subjects:
- kind: ServiceAccount
  name: spire-agent
  namespace: site-west
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
  namespace: site-west
data:
  server.conf: |
    server {
      bind_address = "0.0.0.0"
      bind_port = "8081"
      socket_path = "/tmp/spire-server/private/api.sock"
      trust_domain = "west.demo.example.com"
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
              service_account_allow_list = ["site-west:spire-agent"]
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
  namespace: site-west
data:
  agent.conf: |
    agent {
      data_dir = "/run/spire/agent"
      log_level = "INFO"
      server_address = "spire-server.site-west.svc.cluster.local"
      server_port = "8081"
      socket_path = "/run/spire/sockets/agent.sock"
      trust_domain = "west.demo.example.com"
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
  namespace: site-west
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
  namespace: site-west
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
  namespace: site-west
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
          path: /run/spire/west
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
# Wait for both SPIRE servers and agents to be ready
# ---------------------------------------------------------------------------
echo "Waiting for SPIRE servers to be ready..."
kubectl rollout status statefulset/spire-server -n site-east
kubectl rollout status statefulset/spire-server -n site-west

echo "Waiting for SPIRE agents to be ready..."
kubectl rollout status daemonset/spire-agent -n site-east
kubectl rollout status daemonset/spire-agent -n site-west

# ---------------------------------------------------------------------------
# SPIFFE Bundle Federation
# Must happen before workload entries that use -federatesWith, because SPIRE
# validates that the referenced foreign trust domain bundle already exists.
# ---------------------------------------------------------------------------
echo "--- Federating SPIFFE bundles ---"

WEST_BUNDLE=$(kubectl exec -n site-west statefulset/spire-server -c spire-server -- \
  /opt/spire/bin/spire-server bundle show -format spiffe)
echo "${WEST_BUNDLE}" | kubectl exec -i -n site-east statefulset/spire-server -c spire-server -- \
  /opt/spire/bin/spire-server bundle set \
  -id spiffe://west.demo.example.com \
  -format spiffe

EAST_BUNDLE=$(kubectl exec -n site-east statefulset/spire-server -c spire-server -- \
  /opt/spire/bin/spire-server bundle show -format spiffe)
echo "${EAST_BUNDLE}" | kubectl exec -i -n site-west statefulset/spire-server -c spire-server -- \
  /opt/spire/bin/spire-server bundle set \
  -id spiffe://east.demo.example.com \
  -format spiffe

# ---------------------------------------------------------------------------
# Register workload entries
# ---------------------------------------------------------------------------
echo "--- Registering workload entries ---"

# East: node alias — matches all k8s_psat agents from the minikube cluster
kubectl exec -n site-east statefulset/spire-server -c spire-server -- \
  /opt/spire/bin/spire-server entry create \
  -spiffeID spiffe://east.demo.example.com/cluster-node \
  -selector k8s_psat:cluster:minikube \
  -node

# East: hello-server workload.
# -federatesWith west causes the SVID's trust bundle to include the west CA,
# so the server can validate client certificates from site-west.
kubectl exec -n site-east statefulset/spire-server -c spire-server -- \
  /opt/spire/bin/spire-server entry create \
  -spiffeID spiffe://east.demo.example.com/hello-server \
  -parentID spiffe://east.demo.example.com/cluster-node \
  -selector k8s:ns:site-east \
  -selector k8s:sa:hello-server \
  -federatesWith spiffe://west.demo.example.com

# West: node alias
kubectl exec -n site-west statefulset/spire-server -c spire-server -- \
  /opt/spire/bin/spire-server entry create \
  -spiffeID spiffe://west.demo.example.com/cluster-node \
  -selector k8s_psat:cluster:minikube \
  -node

# West: hello-client workload.
# -federatesWith east causes the SVID's trust bundle to include the east CA,
# so the client can validate the server certificate from site-east.
kubectl exec -n site-west statefulset/spire-server -c spire-server -- \
  /opt/spire/bin/spire-server entry create \
  -spiffeID spiffe://west.demo.example.com/hello-client \
  -parentID spiffe://west.demo.example.com/cluster-node \
  -selector k8s:ns:site-west \
  -selector k8s:sa:hello-client \
  -federatesWith spiffe://east.demo.example.com

echo ""
echo "SPIRE deployed and federated successfully."
echo ""
echo "Trust domains:"
echo "  site-east -> east.demo.example.com"
echo "  site-west -> west.demo.example.com"
echo ""
echo "SPIFFE IDs:"
echo "  Server (site-east): spiffe://east.demo.example.com/hello-server"
echo "  Client (site-west): spiffe://west.demo.example.com/hello-client"
echo ""
echo "Next: run init-west-client.sh then init-east-client.sh"
