# Keycloak Microservice Authorization Demo

Demonstrates Keycloak-based authentication and authorization across a chain of microservices using OAuth2/OIDC, role-based access control, and token exchange.

## Architecture

```
Browser --> Frontend (8081) --> Microservice 1 (8082) --> Microservice 2 (8083)
                |                       |                        |
                +----------- Keycloak (8080) -------------------+
```

- **Frontend** - Quarkus web app with Keycloak SSO login (authorization code flow). Displays the authenticated user's name and roles, and provides a button to invoke the microservice chain. Downstream errors are handled gracefully.
- **Microservice 1** - Validates the `service1` role, performs an OAuth2 token exchange with Keycloak to obtain a new token scoped to the `microservice2` audience, then calls Microservice 2 with the exchanged token.
- **Microservice 2** - Validates the `service2` role and returns the decoded JWT bearer token as pretty-printed JSON.
- **Keycloak** - Provides SSO, token issuance, role-based authorization, audience-scoped tokens, and standard OAuth2 token exchange (RFC 8693).

## Token Exchange Flow

```
┌─────────┐     ┌──────────┐     ┌──────────────┐     ┌──────────┐     ┌──────────────┐
│ Browser │     │ Frontend │     │ Microservice1 │     │ Keycloak │     │ Microservice2 │
└────┬────┘     └────┬─────┘     └──────┬───────┘     └────┬─────┘     └──────┬───────┘
     │               │                  │                  │                  │
     │  Login (OIDC) │                  │                  │                  │
     │──────────────>│                  │                  │                  │
     │               │  Auth Code Flow  │                  │                  │
     │               │─────────────────────────────────────>                  │
     │               │  Access Token (aud: microservice1)  │                  │
     │               │<─────────────────────────────────────                  │
     │   index.html  │                  │                  │                  │
     │<──────────────│                  │                  │                  │
     │               │                  │                  │                  │
     │ Call Service   │                  │                  │                  │
     │──────────────>│                  │                  │                  │
     │               │  Bearer Token    │                  │                  │
     │               │  (aud: ms1)      │                  │                  │
     │               │─────────────────>│                  │                  │
     │               │                  │ Validate: service1 role             │
     │               │                  │                  │                  │
     │               │                  │  Token Exchange  │                  │
     │               │                  │  (RFC 8693)      │                  │
     │               │                  │─────────────────>│                  │
     │               │                  │  New Token       │                  │
     │               │                  │  (aud: ms2)      │                  │
     │               │                  │<─────────────────│                  │
     │               │                  │                  │                  │
     │               │                  │  Bearer Token (aud: ms2)            │
     │               │                  │────────────────────────────────────>│
     │               │                  │                  │ Validate: service2 role
     │               │                  │                  │                  │
     │               │                  │  Decoded JWT (pretty JSON)          │
     │               │                  │<────────────────────────────────────│
     │               │  Aggregated      │                  │                  │
     │               │  Response        │                  │                  │
     │               │<─────────────────│                  │                  │
     │  Traffic Flow  │                  │                  │                  │
     │  (all JWTs +   │                  │                  │                  │
     │   responses)   │                  │                  │                  │
     │<──────────────│                  │                  │                  │
```

1. User authenticates via the frontend using the OIDC authorization code flow
2. Frontend receives an access token with the `microservice1` audience
3. Frontend passes the user's access token as a Bearer token to Microservice 1
4. Microservice 1 validates the token and checks for the `service1` role
5. Microservice 1 performs an OAuth2 token exchange with Keycloak, exchanging the user's token for a new one scoped to the `microservice2` audience
6. Microservice 1 calls Microservice 2 with the exchanged token
7. Microservice 2 validates the exchanged token and checks for the `service2` role
8. Each service returns decoded JWT details as pretty-printed JSON, with responses aggregated up the chain

## Pre-configured Users

| User  | Email             | Password | Roles              | Expected Behavior                          |
|-------|-------------------|----------|--------------------|--------------------------------------------|
| ryan  | ryan@example.og   | password | service1, service2 | Full access through the entire chain       |
| alice | alice@example.og  | password | service1           | Access to Microservice 1, denied at 2      |
| alan  | alan@example.og   | password | (none)             | Denied at Microservice 1                   |

## Keycloak Configuration

All configuration is defined in `keycloak/demo-realm.json` and imported on startup:

- **Realm**: `demo`
- **Clients**:
  - `frontend` - Public client for the browser (authorization code flow). Includes an audience mapper to add `microservice1` to issued tokens.
  - `microservice1` - Confidential client with `standard.token.exchange.enabled`. Includes an audience mapper to add `microservice2` to exchanged tokens.
  - `microservice2` - Confidential bearer-only client.
- **Scope Mappings**: The `microservice1` client is mapped to the `service1` and `service2` realm roles, ensuring exchanged tokens carry the user's roles.
- **Hostname**: `KC_HOSTNAME` is set to `http://localhost:8080` for browser redirects, with `KC_HOSTNAME_BACKCHANNEL_DYNAMIC` enabled so containers can reach Keycloak via the Docker network hostname.

## Prerequisites

- Docker and Docker Compose
- Java 21 (for local development only)
- Maven 3.9+ (for local development only)

## Quick Start

```bash
docker compose up --build
```

Once all services are healthy:

1. Open http://localhost:8081
2. You will be redirected to Keycloak login
3. Log in with one of the test users (e.g., `ryan` / `password`)
4. Click **Call Microservice** to invoke the service chain
5. The response shows the full traffic flow: decoded JWTs at each hop and the aggregated responses from each service

## Keycloak Admin Console

Access the Keycloak admin console at http://localhost:8080 with credentials `admin` / `admin`.

## Cleanup

```bash
docker compose down
```

## Tech Stack

- Java 21
- Quarkus 3.23.3
- Keycloak (nightly)
- Docker Compose
