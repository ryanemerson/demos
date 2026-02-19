# Quarkus JGroups Demo

A Quarkus application demonstrating JGroups clustering with TCPPING discovery protocol. This application allows nodes to form a cluster and exchange messages using JGroups.

## Features

- JGroups integration with TCPPING for static host discovery
- Configurable initial hosts via Quarkus properties
- REST API for cluster information and message broadcasting
- Containerized deployment with Docker
- TCP-based clustering (suitable for containerized environments)

## Prerequisites

- JDK 17 or later
- Maven 3.8+
- Docker (for containerized deployment)

## Configuration

The application can be configured via `src/main/resources/application.properties` or environment variables:

| Property | Default | Description |
|----------|---------|-------------|
| `jgroups.cluster.name` | `quarkus-cluster` | Name of the JGroups cluster |
| `jgroups.tcpping.initial_hosts` | `localhost[7800]` | Comma-separated list of initial cluster members |
| `jgroups.bind_port` | `7800` | Port for JGroups communication |
| `jgroups.bind_addr` | `site_local,loopback` | Bind address for JGroups (uses interface matching) |
| `quarkus.http.port` | `8080` | HTTP port for REST API |

### TCPPING Initial Hosts Format

The `initial_hosts` property accepts a comma-separated list of host[port] entries:
```
host1[7800],host2[7800],host3[7800]
```

### Bind Address Format (JGroups 5.x)

The `bind_addr` property uses JGroups interface matching syntax:
- `site_local` - Matches site-local addresses (e.g., 192.168.x.x, 10.x.x.x)
- `loopback` - Matches loopback interface (127.0.0.1)
- `match-interface:eth0` - Matches specific network interface
- Specific IP address (e.g., `192.168.1.100`)

JGroups will try each option in order. For containerized environments, you typically want to set this to the container's hostname or a specific IP.

## Building the Application

Build the application using Maven:

```bash
mvn clean package
```

Or use the provided build script:

```bash
./build.sh
```

## Running Locally

### Single Instance

```bash
java -jar target/quarkus-app/quarkus-run.jar
```

### Multiple Instances (Local Cluster)

Use the provided script to quickly start a 3-node cluster:

```bash
./run-cluster.sh
```

Or manually start each node in separate terminals:

Terminal 1 (Node 1):
```bash
java -Djgroups.bind_port=7800 \
     -Djgroups.tcpping.initial_hosts="localhost[7800],localhost[7801]" \
     -Dquarkus.http.port=8080 \
     -jar target/quarkus-app/quarkus-run.jar
```

Terminal 2 (Node 2):
```bash
java -Djgroups.bind_port=7801 \
     -Djgroups.tcpping.initial_hosts="localhost[7800],localhost[7801]" \
     -Dquarkus.http.port=8081 \
     -jar target/quarkus-app/quarkus-run.jar
```

## Running with Docker

### Build the Docker Image

```bash
mvn clean package
docker build -t quarkus-jgroups-demo .
```

### Run with Docker Compose

The easiest way to test a multi-node cluster with Docker:

```bash
docker-compose up --build
```

This will start a 3-node cluster with the following endpoints:
- Node 1: http://localhost:8080
- Node 2: http://localhost:8081
- Node 3: http://localhost:8082

To stop the cluster:
```bash
docker-compose down
```

### Run Single Container

```bash
docker run -it --rm \
  -p 8080:8080 \
  -p 7800:7800 \
  -e jgroups.tcpping.initial_hosts="localhost[7800]" \
  quarkus-jgroups-demo
```

### Run Multiple Containers (Docker Network)

Create a Docker network:
```bash
docker network create jgroups-network
```

Run Node 1:
```bash
docker run -d --name node1 \
  --network jgroups-network \
  -p 8080:8080 \
  -e jgroups.tcpping.initial_hosts="node1[7800],node2[7800]" \
  -e jgroups.bind_addr=node1 \
  quarkus-jgroups-demo
```

Run Node 2:
```bash
docker run -d --name node2 \
  --network jgroups-network \
  -p 8081:8080 \
  -e jgroups.tcpping.initial_hosts="node1[7800],node2[7800]" \
  -e jgroups.bind_addr=node2 \
  quarkus-jgroups-demo
```

## REST API

### Get Cluster Information

```bash
curl http://localhost:8080/cluster/info
```

Response:
```json
{
  "localAddress": "node1-12345",
  "viewId": "node1-12345|1",
  "members": ["node1-12345", "node2-67890"],
  "memberCount": 2
}
```

### Send Message to Cluster

```bash
curl -X POST http://localhost:8080/cluster/send \
  -H "Content-Type: application/json" \
  -d '{"message": "Hello from node1"}'
```

### Get Received Messages

```bash
curl http://localhost:8080/cluster/messages
```

Response:
```json
[
  "[node1-12345] Hello from node1",
  "[node2-67890] Hello from node2"
]
```

### Clear Received Messages

```bash
curl -X DELETE http://localhost:8080/cluster/messages
```

## Testing the Cluster

1. Start two or more instances as described above
2. Check cluster membership:
   ```bash
   curl http://localhost:8080/cluster/info
   ```
3. Send a message from one node:
   ```bash
   curl -X POST http://localhost:8080/cluster/send \
     -H "Content-Type: application/json" \
     -d '{"message": "Test message"}'
   ```
4. Verify the message was received on all nodes:
   ```bash
   curl http://localhost:8080/cluster/messages
   curl http://localhost:8081/cluster/messages
   ```

## Kubernetes Deployment

For Kubernetes deployments, set the `initial_hosts` to a comma-separated list of pod FQDNs or service names:

```yaml
env:
  - name: jgroups.tcpping.initial_hosts
    value: "jgroups-0.jgroups-headless.default.svc.cluster.local[7800],jgroups-1.jgroups-headless.default.svc.cluster.local[7800]"
```

Consider using a headless service and StatefulSet for predictable pod names.

## Troubleshooting

### Nodes Not Forming Cluster

- Verify the `initial_hosts` includes all node addresses
- Check network connectivity between nodes
- Ensure port 7800 (or configured port) is accessible
- Review JGroups logs for connection errors

### Container Networking Issues

- Use container names or IPs in `initial_hosts` when using Docker networks
- Ensure containers are on the same network
- Check that ports are properly exposed and mapped

## Development Mode

Run in development mode with live reload:

```bash
./mvnw quarkus:dev
```

Access the dev UI at http://localhost:8080/q/dev

## License

This is a demonstration project.
