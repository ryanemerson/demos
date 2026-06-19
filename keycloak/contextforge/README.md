# MCP + ContextForge Gateway + Keycloak OIDC Demo

A demo application showcasing remote MCP (Model Context Protocol) client-server communication routed through an [IBM ContextForge](https://github.com/IBM/mcp-context-forge) gateway and secured with Keycloak OIDC authentication.

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
         +------------+    +------+-------+    +--------------+
         |  CLI        |    | ContextForge |    |  MCP Server  |
         |  ChatBot    |--->|  Gateway     |--->|  (Quarkus)   |
         |  (Quarkus)  | MCP|  port: 4444  | MCP|  port: 8085  |
         |  localhost   | SSE|  (proxy)     | SSE|              |
         +------+------+    +--------------+    +--------------+
                |
                v
         +-------------------+
         | OpenAI-compatible |
         | LLM endpoint      |
         | port: 8888        |
         +-------------------+
```

All Docker services use **host networking** so that Keycloak, the ContextForge gateway, the MCP server, and the chatbot share the same `localhost` — this eliminates OIDC issuer mismatches between internal and external hostnames.

**Components:**

- **Keycloak 26.6.1** — Authorization server with a `demo` realm, three clients, and two test users
- **ContextForge Gateway** — IBM MCP gateway that proxies and federates MCP servers, with Keycloak SSO integration and authenticated MCP access
- **MCP Server** — Quarkus app exposing two MCP tools (`who-am-i` and `server-secret`), secured with OIDC bearer token validation
- **CLI ChatBot** — Quarkus Picocli app that authenticates via browser-based OIDC login (Authorization Code + PKCE), then provides an interactive LLM chat with access to the secured MCP tools through the gateway. Connects to any OpenAI-compatible LLM endpoint (e.g., [RamaLama](https://github.com/containers/ramalama) serving a local model)

**Authentication flow:**

1. The chatbot authenticates the user via Keycloak (browser-based OIDC Authorization Code + PKCE)
2. The resulting Keycloak access token is sent as a Bearer token to the ContextForge gateway
3. The gateway validates the token via Keycloak SSO integration and passes it through to the MCP server
4. The MCP server validates the same token and enforces role-based access control (RBAC)
5. Users with the `admin` role can access all tools; users with only the `user` role are restricted

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
├── docker-compose.yml                   # Keycloak + MCP server + ContextForge gateway
├── keycloak/
│   └── demo-realm.json                  # Realm with clients, roles, users
├── scripts/
│   └── register-mcp-server.sh           # Init script to register MCP server with gateway
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

## Keycloak Clients

| Client ID      | Type         | Purpose                                    |
|----------------|--------------|--------------------------------------------|
| chatbot-cli    | Public       | Chatbot OIDC login (Auth Code + PKCE)      |
| mcp-server     | Confidential | MCP server bearer token validation         |
| mcp-gateway    | Confidential | ContextForge gateway Keycloak SSO          |

## Quick Start

### 1. Build the project

```bash
mvn clean package -DskipTests
```

### 2. Start Keycloak, MCP Server, and ContextForge Gateway

```bash
docker compose up -d --build
```

Wait for all services to become healthy:

```bash
docker compose ps
# keycloak should show "healthy"
# contextforge-gateway should show "healthy"
# gateway-init should show "exited (0)" — it registers the MCP server with the gateway and exits
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

Your browser will open to the Keycloak login page. Log in as `alice` (password: `alice`) for full access, or `bob` (password: `bob`) for regular user access.

### 6. Chat

Once logged in, try these prompts:

```
You: Who am I?
You: What is the server secret?
```

Type `quit` or `exit` to end the session. The chatbot will automatically log you out of Keycloak, so the next launch requires a fresh login.

## Configuration

All chatbot settings are configurable via environment variables or `application.properties`:

| Variable / Property                        | Default                             | Description                                |
|--------------------------------------------|-------------------------------------|--------------------------------------------|
| `OIDC_AUTH_SERVER_URL`                     | `http://localhost:8180/realms/demo` | Keycloak realm URL                         |
| `OIDC_CLIENT_ID`                           | `chatbot-cli`                       | OIDC client ID                             |
| `MCP_SERVER_URL`                           | `http://localhost:4444/mcp/sse`     | ContextForge gateway MCP SSE endpoint      |
| `quarkus.langchain4j.openai.base-url`      | `http://localhost:8888/v1`          | OpenAI-compatible LLM endpoint             |
| `quarkus.langchain4j.openai.api-key`       | `dummy-key`                         | API key (use `dummy-key` for local)        |
| `quarkus.langchain4j.openai.max-tokens`    | `2048`                              | Maximum response tokens                    |
| `quarkus.langchain4j.openai.temperature`   | `0.7`                               | Sampling temperature                       |
| `quarkus.langchain4j.openai.timeout`       | `60s`                               | Request timeout                            |

## Verification

### Verify Keycloak is running

```bash
curl -s http://localhost:8180/realms/demo | jq .realm
# Expected: "demo"
```

### Verify ContextForge gateway is running

```bash
curl -s http://localhost:4444/health
# Expected: healthy response
```

### Verify gateway rejects unauthenticated MCP requests

```bash
curl -v http://localhost:4444/mcp/sse
# Expected: 401 Unauthorized
```

### Verify MCP server is registered with the gateway

```bash
curl -s http://localhost:4444/gateways -u admin:admin | jq .
# Expected: list containing "mcp-server"
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

## ContextForge Gateway Admin UI

The ContextForge admin UI is available at [http://localhost:4444](http://localhost:4444). There are two ways to log in:

- **Keycloak SSO** — Click the Keycloak login button and sign in as any demo user (e.g., `alice` / `alice`). New users are auto-provisioned in the gateway on first login.
- **Basic Auth** — Use `admin` / `admin` to log in as the platform admin.

From the UI you can:

- View and manage registered MCP servers
- Browse the federated tool catalog (you should see `mcp_server_who_am_i` and `mcp_server_server_secret`)
- Test tools directly from the browser
- Monitor real-time request logs

## Shutting Down

```bash
docker compose down
```

## Troubleshooting

- **Browser doesn't open**: Copy the URL printed in the terminal and paste it into your browser manually.
- **Token exchange fails**: Ensure Keycloak is healthy (`docker compose ps`) and port 8080 is free for the callback server.
- **MCP connection refused**: Verify the ContextForge gateway is running with `docker compose ps` and port 4444 is accessible.
- **401 on MCP calls**: Check that the access token hasn't expired (default: 5 minutes). Restart the chatbot to re-authenticate.
- **403 on server-secret**: You're logged in as a user without the `admin` role. Log in as `alice` instead.
- **Port conflicts**: Host networking requires ports 8180 (Keycloak), 9000 (Keycloak management), 8085 (MCP server), 4444 (ContextForge gateway), 8080 (chatbot callback), and 8888 (LLM endpoint) to be free on the host.
- **LLM not responding**: Verify your OpenAI-compatible endpoint is running on port 8888, or override `quarkus.langchain4j.openai.base-url`.
- **Gateway init fails**: Check `docker compose logs gateway-init` — the MCP server must be reachable on port 8085 for registration.

## Technology Stack

- Java 21
- Quarkus 3.34.6
- Quarkus LangChain4j 1.8.4 (OpenAI-compatible LLM integration)
- Quarkus MCP Server 1.10.3 (SSE transport)
- [IBM ContextForge](https://github.com/IBM/mcp-context-forge) v1.0.3 (MCP Gateway)
- Keycloak 26.6.1
- Docker Compose (host networking)
