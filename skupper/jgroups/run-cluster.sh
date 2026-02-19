#!/bin/bash

# Script to run a local 3-node JGroups cluster for testing

INITIAL_HOSTS="localhost[7800],localhost[7801],localhost[7802]"

echo "Starting 3-node JGroups cluster..."
echo "Initial hosts: $INITIAL_HOSTS"
echo ""
echo "To send messages, use:"
echo "  curl -X POST http://localhost:8080/cluster/send -H 'Content-Type: application/json' -d '{\"message\": \"Hello\"}'"
echo ""
echo "To view cluster info:"
echo "  curl http://localhost:8080/cluster/info"
echo "  curl http://localhost:8081/cluster/info"
echo "  curl http://localhost:8082/cluster/info"
echo ""
echo "Press Ctrl+C to stop all nodes"
echo ""

# Cleanup function
cleanup() {
    echo ""
    echo "Stopping all nodes..."
    jobs -p | xargs -r kill
    exit 0
}

trap cleanup SIGINT SIGTERM

# Start Node 1
echo "Starting Node 1 (HTTP: 8080, JGroups: 7800)..."
java -Djgroups.bind_port=7800 \
     -Djgroups.tcpping.initial_hosts="$INITIAL_HOSTS" \
     -Dquarkus.http.port=8080 \
     -jar target/quarkus-app/quarkus-run.jar > node1.log 2>&1 &

# Start Node 2
echo "Starting Node 2 (HTTP: 8081, JGroups: 7801)..."
java -Djgroups.bind_port=7801 \
     -Djgroups.tcpping.initial_hosts="$INITIAL_HOSTS" \
     -Dquarkus.http.port=8081 \
     -jar target/quarkus-app/quarkus-run.jar > node2.log 2>&1 &

# Start Node 3
echo "Starting Node 3 (HTTP: 8082, JGroups: 7802)..."
java -Djgroups.bind_port=7802 \
     -Djgroups.tcpping.initial_hosts="$INITIAL_HOSTS" \
     -Dquarkus.http.port=8082 \
     -jar target/quarkus-app/quarkus-run.jar > node3.log 2>&1 &

echo ""
echo "All nodes started! Logs are in node1.log, node2.log, node3.log"
echo "Waiting for cluster to form..."
sleep 5

# Wait for all background jobs
wait
