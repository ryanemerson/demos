# Decoupled Authorization with Keycloak and Cerbos

A demo application that uses [Keycloak](https://www.keycloak.org/) for identity management and
[Cerbos](https://www.cerbos.dev/) as an external [AuthZen](https://openid.github.io/authzen/)-compatible
Policy Decision Point (PDP) for authorization. Authorization decisions are made directly from the
application to Cerbos using the AuthZen Access Evaluation API, with Keycloak used exclusively for
authentication via OIDC.

## Overview

This project demonstrates the **post-fetch authorization model**: after authenticating a user via
Keycloak OIDC, the application fetches any required resource data and then makes an AuthZen
evaluation call to Cerbos with the full context. This avoids querying Keycloak more than necessary
for user data.

### Architecture

```
  Browser
    |
    v
+---------+   OIDC (authentication)   +----------+
| Quarkus |<------------------------->| Keycloak |
|   App   |                           | (IdP)    |
+---------+                           +----------+
    |                                       |
    | AuthZen HTTP                          | Admin API
    | (authorization)                       | (user lookup)
    v                                       |
+--------+                                  |
| Cerbos |                            (fetch target
|  PDP   |                             user attrs)
+--------+
```

1. A user authenticates via Keycloak OIDC (authorization code flow).
2. The application extracts user identity and roles from the OIDC tokens.
3. If the user is viewing another user's profile, the application fetches the target user's
   attributes from Keycloak via the admin API (post-fetch).
4. An AuthZen evaluation request is sent directly to Cerbos with subject, resource, and action.
5. Cerbos evaluates the request against its policy rules and returns a decision.
6. The application grants or denies access accordingly.

### Project structure

| Directory | Description |
|---|---|
| **app** | Quarkus demo application with OIDC authentication and AuthZen authorization |
| **keycloak** | Realm configuration for Keycloak import |
| **cerbos** | Cerbos configuration and authorization policies |

## AuthZen Client

The `AuthZenClient` is a CDI-managed bean (`@ApplicationScoped`) that handles all communication
with the Cerbos PDP via the AuthZen Access Evaluation API.

### Configuration

The client is configured via `application.properties`:

| Property | Default | Description |
|---|---|---|
| `authzen.scheme` | `http` | URL scheme (`http` or `https`) |
| `authzen.host` | `localhost` | Hostname of the AuthZen PDP |
| `authzen.port` | `3592` | Port of the AuthZen PDP |

### Endpoint discovery

On startup, the client discovers the evaluation endpoint via the
`/.well-known/authzen-configuration` well-known endpoint. If discovery fails, it falls back to
the default path `/access/v1/evaluation`.

### Usage

Authorization checks are performed using a fluent builder:

```java
boolean permitted = authZenClient.evaluate(
        AuthZenClient.EvaluationRequest.builder()
                .subject("user", currentUser)
                .subjectProperty("team", currentUserTeam)
                .subjectProperty("kc.realm.roles", securityIdentity.getRoles())
                .resource("user_profile", username)
                .resourceProperty("team", profileUserTeam)
                .action("view")
                .build()
);
```

## Demo

### Prerequisites

- Java 25+
- Maven
- Docker & Docker Compose

### Users

| Username | Password | Team | Roles |
|---|---|---|---|
| alice | password | engineering | user |
| bob | password | marketing | user |
| charlie | password | engineering | user, manager |

### Cerbos policy rules

The Cerbos policy (`cerbos/policies/user_profile.yaml`) implements the following rules for
the `user_profile` resource type:

1. **Own profile** -- any user can view and edit their own profile
   (`resource.id == principal.id`)
2. **Team manager** -- a user with the `manager` role can view profiles of users on the same
   team

### Running the demo

```bash
# Build the app and start all services
docker compose up --build
```

Once all services are healthy:

- **Demo app**: http://localhost:8081
- **Keycloak admin console**: http://localhost:8080 (admin / admin)

### Testing authorization

1. Open http://localhost:8081 and log in as **alice** (password: `password`).
   You are redirected to `/users/alice` -- your own profile.

2. Navigate to `/users/bob`. Access is **denied** because alice is not a manager.

3. Log out and log in as **charlie** (password: `password`).
   Navigate to `/users/alice`. Access is **allowed** because charlie is a manager on the same team
   (engineering).

4. Navigate to `/users/bob`. Access is **denied** because bob is on the marketing team.

### Debugging

Remote debugging is enabled for both Keycloak (port 5006) and the Quarkus app (port 5005).

To enable debug logging for the AuthZen client, set the environment variable:

```
QUARKUS_LOG_CATEGORY__COM_EXAMPLE_DEMO_AUTHORIZATION__LEVEL=DEBUG
```

This outputs the AuthZen evaluation requests, PDP responses, and endpoint discovery details.
