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
              OIDC Device          |     Bearer Token
              Auth Flow           |     Validation
                                  |
         +------------+    +------+-------+
         |  CLI        |    |  MCP Server  |
         |  ChatBot    |--->|  (Quarkus)   |
         |  (Quarkus)  | MCP|  port: 8085  |
         |  localhost  | SSE|              |
         +------+------+    +--------------+
                |
                v
         +-------------------+
         | OpenAI-compatible |
         | LLM endpoint      |
         | port: 8888        |
         +-------------------+
```

All Docker services use **host networking** so that Keycloak, the MCP server, and the chatbot share the same `localhost` — this eliminates OIDC issuer mismatches between internal and external hostnames.

**Components:**

- **Keycloak 26.6.1** — Authorization server with a `demo` realm, two clients, and two test users
- **MCP Server** — Quarkus app exposing two MCP tools (`who-am-i` and `server-secret`), secured with OIDC bearer token validation
- **CLI ChatBot** — Quarkus Picocli app that authenticates via the OAuth 2.0 Device Authorization flow (RFC 8628), then provides an interactive LLM chat with access to the secured MCP tools. Connects to any OpenAI-compatible LLM endpoint (e.g., [RamaLama](https://github.com/containers/ramalama) serving a local model)

## Prerequisites

- Java 21+
- Maven 3.9+
- Docker and Docker Compose
- Linux host (required for `network_mode: host`)
- An OpenAI-compatible LLM endpoint (e.g., [RamaLama](https://github.com/containers/ramalama) with a local model like `granite-3.3-8b-instruct`)

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
│       │   ├── OidcAuthService.java     # Device Authorization flow (login + logout)
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

### 3. Start an OpenAI-compatible LLM endpoint

Start a local LLM using [RamaLama](https://github.com/containers/ramalama) or any OpenAI-compatible server on port 8888:

```bash
ramalama serve --port 8888 qwen3:4b
```

### 4. Run the ChatBot

```bash
java -jar chatbot/target/quarkus-app/quarkus-run.jar
```

### 5. Log In

The chatbot will display a verification URL and a user code. Your browser will open automatically — log in as `alice` (password: `alice`) for full access, or `bob` (password: `bob`) for regular user access, then enter the displayed code when prompted.

### 6. Chat

Once logged in, try these prompts:

```
You: Who am I?
You: What is the server secret?
```

Type `quit` or `exit` to end the session. The chatbot will automatically log you out of Keycloak, so the next launch requires a fresh login.

## Configuration

All chatbot settings are configurable via environment variables or `application.properties`:

| Variable / Property                        | Default                             | Description                           |
|--------------------------------------------|-------------------------------------|---------------------------------------|
| `OIDC_AUTH_SERVER_URL`                     | `http://localhost:8180/realms/demo` | Keycloak realm URL                    |
| `OIDC_CLIENT_ID`                           | `chatbot-cli`                       | OIDC client ID                        |
| `MCP_SERVER_URL`                           | `http://localhost:8085/mcp/sse`     | MCP server SSE endpoint               |
| `quarkus.langchain4j.openai.base-url`      | `http://localhost:8888/v1`          | OpenAI-compatible LLM endpoint        |
| `quarkus.langchain4j.openai.api-key`       | `dummy-key`                         | API key (use `dummy-key` for local)   |
| `quarkus.langchain4j.openai.max-tokens`    | `2048`                              | Maximum response tokens               |
| `quarkus.langchain4j.openai.temperature`   | `0.7`                               | Sampling temperature                  |
| `quarkus.langchain4j.openai.timeout`       | `60s`                               | Request timeout                       |

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
- **Token exchange fails**: Ensure Keycloak is healthy (`docker compose ps`).
- **MCP connection refused**: Verify the MCP server is running with `docker compose ps` and port 8085 is accessible.
- **401 on MCP calls**: Check that the access token hasn't expired (default: 5 minutes). Restart the chatbot to re-authenticate.
- **403 on server-secret**: You're logged in as a user without the `admin` role. Log in as `alice` instead.
- **Port conflicts**: Host networking requires ports 8180 (Keycloak), 9000 (Keycloak management), 8085 (MCP server), and 8888 (LLM endpoint) to be free on the host.
- **LLM not responding**: Verify your OpenAI-compatible endpoint is running on port 8888, or override `quarkus.langchain4j.openai.base-url`.

## Technology Stack

- Java 21
- Quarkus 3.34.6
- Quarkus LangChain4j 1.8.4 (OpenAI-compatible LLM integration)
- Quarkus MCP Server 1.10.3 (SSE transport)
- Keycloak 26.6.1
- Docker Compose (host networking)
