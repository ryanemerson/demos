# MCP + Keycloak OIDC Demo

A demo application showcasing remote MCP (Model Context Protocol) client-server communication secured with Keycloak OIDC authentication.

## Architecture

```
                         +-----------------+
                         |  Keycloak 26.6  |
                         |  (Auth Server)  |
                         |  port: 8180     |
                         +--------+--------+
                                  |
              OIDC Auth Code      |     Bearer Token
              Flow (PKCE)         |     Validation
                                  |
         +------------+    +------+-------+
         |  CLI        |    |  MCP Server  |
         |  ChatBot    |--->|  (Quarkus)   |
         |  (Quarkus)  | MCP|  port: 8085  |
         |  localhost   | SSE|              |
         +------+------+    +--------------+
                |
                v
         +--------------+
         |  Vertex AI    |
         |  Claude LLM   |
         +--------------+
```

All Docker services use **host networking** so that Keycloak, the MCP server, and the chatbot share the same `localhost` — this eliminates OIDC issuer mismatches between internal and external hostnames.

**Components:**

- **Keycloak 26.6.1** — Authorization server with a `demo` realm, two clients, and two test users
- **MCP Server** — Quarkus app exposing two MCP tools (`who-am-i` and `server-secret`), secured with OIDC bearer token validation
- **CLI ChatBot** — Quarkus Picocli app that authenticates via browser-based OIDC login (Authorization Code + PKCE), then provides an interactive LLM chat with access to the secured MCP tools

## Prerequisites

- Java 21+
- Maven 3.9+
- Docker and Docker Compose
- Linux host (required for `network_mode: host`)
- A Google Cloud project with the Vertex AI API enabled and access to Claude models
- Google Cloud credentials configured via `gcloud auth application-default login`

## Project Structure

```
.
├── pom.xml                              # Parent POM (multi-module)
├── docker-compose.yml                   # Keycloak + MCP server (host networking)
├── keycloak/
│   └── demo-realm.json                  # Realm with clients, roles, users
├── mcp-server/
│   ├── pom.xml                          # Standalone POM (builds independently in Docker)
│   ├── Dockerfile
│   └── src/main/
│       ├── java/com/demo/mcp/server/
│       │   └── McpServerTools.java      # @Tool methods with @Authenticated / @RolesAllowed
│       └── resources/application.properties
├── chatbot/
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/demo/chatbot/
│       │   ├── ChatBotCommand.java      # Picocli @Command entry point
│       │   ├── AiAssistant.java         # @RegisterAiService with @McpToolBox
│       │   ├── ChatModelProducer.java   # Supplier<ChatModel> using Vertex AI Anthropic
│       │   ├── OidcAuthService.java     # Auth Code + PKCE flow (login + logout)
│       │   └── McpTokenProvider.java    # McpClientAuthProvider — injects Bearer token
│       └── resources/application.properties
└── README.md
```

## Test Users

| Username | Password | Roles       | Can access `server-secret`? |
|----------|----------|-------------|-----------------------------|
| alice    | alice    | admin, user | Yes                         |
| bob      | bob      | user        | No (403 Forbidden)          |

## Quick Start

### 1. Build the project

```bash
mvn clean package -DskipTests
```

### 2. Start Keycloak and MCP Server

```bash
docker compose up -d --build
```

Wait for Keycloak to become healthy:

```bash
docker compose ps
# keycloak should show "healthy"
```

### 3. Authenticate with Google Cloud

If you haven't already, set up Application Default Credentials:

```bash
gcloud auth application-default login
```

This creates `~/.config/gcloud/application_default_credentials.json`, which the chatbot reads automatically.

### 4. Run the ChatBot

```bash
export ANTHROPIC_VERTEX_PROJECT_ID=your-gcp-project-id

java -jar chatbot/target/quarkus-app/quarkus-run.jar
```

### 5. Log In

Your browser will open to the Keycloak login page. Log in as `alice` (password: `alice`) for full access, or `bob` (password: `bob`) for regular user access.

### 6. Chat

Once logged in, try these prompts:

```
You: Who am I?
You: What is the server secret?
```

Type `quit` or `exit` to end the session. The chatbot will automatically log you out of Keycloak, so the next launch requires a fresh login.

## Configuration

All chatbot settings are configurable via environment variables:

| Variable                      | Default                             | Description                    |
|-------------------------------|-------------------------------------|--------------------------------|
| `ANTHROPIC_VERTEX_PROJECT_ID` | *(required)*                        | Google Cloud project ID        |
| `CLOUD_ML_REGION`             | `us-east5`                          | Vertex AI region               |
| `VERTEX_AI_MODEL`             | `claude-opus-4-6`            | Claude model name on Vertex AI |
| `OIDC_AUTH_SERVER_URL`        | `http://localhost:8180/realms/demo` | Keycloak realm URL             |
| `OIDC_CLIENT_ID`              | `chatbot-cli`                       | OIDC client ID                 |
| `MCP_SERVER_URL`              | `http://localhost:8085/mcp/sse`     | MCP server SSE endpoint        |

## Verification

### Verify Keycloak is running

```bash
curl -s http://localhost:8180/realms/demo | jq .realm
# Expected: "demo"
```

### Verify MCP Server rejects unauthenticated requests

```bash
curl -v http://localhost:8085/mcp/sse
# Expected: 401 Unauthorized
```

### Obtain a token manually (for testing)

```bash
ACCESS_TOKEN=$(curl -s -X POST http://localhost:8180/realms/demo/protocol/openid-connect/token \
  -d "grant_type=password" \
  -d "client_id=chatbot-cli" \
  -d "username=alice" \
  -d "password=alice" \
  -d "scope=openid" | jq -r .access_token)

echo $ACCESS_TOKEN
```

### Inspect the token claims

```bash
echo $ACCESS_TOKEN | cut -d. -f2 | base64 -d 2>/dev/null | jq .
# Look for realm_access.roles containing "admin"
```

### Test MCP Server with the token

```bash
curl -N -H "Authorization: Bearer $ACCESS_TOKEN" http://localhost:8085/mcp/sse
# Should return MCP SSE messages, not 401
```

### Verify role-based access (bob has no admin role)

```bash
BOB_TOKEN=$(curl -s -X POST http://localhost:8180/realms/demo/protocol/openid-connect/token \
  -d "grant_type=password" \
  -d "client_id=chatbot-cli" \
  -d "username=bob" \
  -d "password=bob" \
  -d "scope=openid" | jq -r .access_token)

echo $BOB_TOKEN | cut -d. -f2 | base64 -d 2>/dev/null | jq .realm_access
# Should NOT contain "admin"
```

### End-to-end chatbot test

1. Run the chatbot and log in as **alice**: ask "Who am I?" (returns "alice") and "What is the server secret?" (returns the container hostname)
2. Restart the chatbot and log in as **bob**: ask "Who am I?" (returns "bob") and "What is the server secret?" (should fail with an authorization error)

## Shutting Down

```bash
docker compose down
```

## Troubleshooting

- **Browser doesn't open**: Copy the URL printed in the terminal and paste it into your browser manually.
- **Token exchange fails**: Ensure Keycloak is healthy (`docker compose ps`) and port 8080 is free for the callback server.
- **MCP connection refused**: Verify the MCP server is running with `docker compose ps` and port 8085 is accessible.
- **401 on MCP calls**: Check that the access token hasn't expired (default: 5 minutes). Restart the chatbot to re-authenticate.
- **403 on server-secret**: You're logged in as a user without the `admin` role. Log in as `alice` instead.
- **Port conflicts**: Host networking requires ports 8180 (Keycloak), 9000 (Keycloak management), 8085 (MCP server), and 8080 (chatbot callback) to be free on the host.

## Technology Stack

- Java 21
- Quarkus 3.34.6
- LangChain4j 1.8.4 / Vertex AI Anthropic 1.12.2-beta22 (Claude via Google Vertex AI)
- Quarkus MCP Server 1.10.3 (SSE transport)
- Keycloak 26.6.1
- Docker Compose (host networking)
