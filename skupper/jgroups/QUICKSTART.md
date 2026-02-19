# Quick Start Guide

Get up and running with the Quarkus JGroups demo in 5 minutes.

## Option 1: Docker Compose (Recommended)

The fastest way to see a working cluster:

```bash
# Build and start a 3-node cluster
mvn clean package
docker-compose up --build

# In another terminal, test the cluster
curl http://localhost:8080/cluster/info
curl http://localhost:8081/cluster/info

# Send a message
curl -X POST http://localhost:8080/cluster/send \
  -H "Content-Type: application/json" \
  -d '{"message": "Hello from Docker!"}'

# Check messages on all nodes
curl http://localhost:8080/cluster/messages
curl http://localhost:8081/cluster/messages
curl http://localhost:8082/cluster/messages

# Cleanup
docker-compose down
```

## Option 2: Local Java Processes

Run multiple nodes on your local machine:

```bash
# Build the application
mvn clean package

# Start a 3-node cluster
./run-cluster.sh

# In another terminal, test the cluster
curl http://localhost:8080/cluster/info

# Send a message
curl -X POST http://localhost:8080/cluster/send \
  -H "Content-Type: application/json" \
  -d '{"message": "Hello World!"}'

# Check messages
curl http://localhost:8080/cluster/messages
```

## What's Happening?

1. Each node joins the cluster using TCPPING with the configured `initial_hosts`
2. When a message is sent via the REST API, it's broadcast to all cluster members
3. All nodes receive the message and store it in their local list
4. You can query any node to see all messages received by that node

## Next Steps

- Read the full [README.md](README.md) for detailed configuration options
- Try scaling the cluster up or down
- Experiment with different `initial_hosts` configurations
- Deploy to Kubernetes with Skupper for cross-cluster networking
