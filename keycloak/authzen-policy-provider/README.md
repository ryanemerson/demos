# Keycloak AuthZen Policy Provider

A Keycloak extension that delegates authorization decisions to an external
[AuthZen](https://openid.github.io/authzen/)-compatible Policy Decision Point (PDP). The demo
uses [Cerbos](https://www.cerbos.dev/) as the PDP, but any AuthZen-compliant service can be used.

## Overview

Keycloak's built-in authorization engine supports JavaScript and role-based policies out of the box.
This project adds a new **authzen** policy type that forwards evaluation requests to an external PDP
over HTTP using the AuthZen Access Evaluation API, enabling centralized, vendor-neutral policy
management.

The project is split into three modules:

| Module | Description |
|---|---|
| **provider** | The `AuthZenPolicyProvider` Keycloak SPI and the `AuthZenPropertyMapper` extensibility SPI |
| **user-profile-mapper** | An example `AuthZenPropertyMapper` that enriches requests with target user profile data from the Keycloak user store |
| **app** | A Quarkus demo application that exercises the authorization flow |

## Architecture

```
  Browser
    │
    ▼
┌──────────┐    UMA grant     ┌──────────┐   AuthZen HTTP   ┌────────┐
│  Quarkus ├─────────────────►│ Keycloak ├─────────────────►│ Cerbos │
│   App    │◄─────────────────┤          │◄─────────────────┤  PDP   │
└──────────┘  permit / deny   └──────────┘   decision       └────────┘
```

1. A user requests a protected resource (e.g. `/users/alice`).
2. The Quarkus Policy Enforcer obtains a UMA grant from Keycloak.
3. Keycloak evaluates the configured permissions. When an **authzen** policy is encountered, the
   `AuthZenPolicyProvider` builds an AuthZen request and POSTs it to the external PDP.
4. The PDP evaluates the request against its policy rules and returns a decision.
5. Keycloak grants or denies the permission accordingly.

## AuthZen Policy Provider

### How it works

The provider discovers the PDP's access evaluation endpoint via the
`/.well-known/authzen-configuration` well-known endpoint and caches it for subsequent requests.

An AuthZen evaluation request is constructed from the Keycloak evaluation context:

- **Subject** &mdash; the authenticated user. All identity attributes from the access token are
  forwarded as subject properties.
- **Resource** &mdash; the Keycloak resource being accessed, including its type and attributes.
- **Action** &mdash; derived from the requested UMA scope (defaults to `access`).

### Policy configuration

The policy is configured via Keycloak's policy config map (set in the realm JSON or the admin
console). The following properties are supported:

| Property | Required | Description |
|---|---|---|
| `authzen.host` | Yes | Hostname of the AuthZen PDP |
| `authzen.port` | No | Port (defaults to 80/443 based on scheme) |
| `authzen.scheme` | No | `http` or `https` (default: `http`) |
| `authzen.subject-id-attribute` | No | Identity attribute to use as the subject ID (e.g. `preferred_username`). Falls back to the Keycloak user UUID. |
| `authzen.resource-id-attribute` | No | Evaluation context attribute to use as the resource ID. For URI values the last path segment is extracted. Falls back to the Keycloak resource name. |
| `property-mappers` | No | Comma-separated list of fully-qualified `AuthZenPropertyMapperFactory` class names to apply (see below) |

Example realm JSON policy definition:

```json
{
  "name": "AuthZen Policy",
  "type": "authzen",
  "logic": "POSITIVE",
  "decisionStrategy": "UNANIMOUS",
  "config": {
    "authzen.host": "cerbos",
    "authzen.port": "3592",
    "authzen.scheme": "http",
    "authzen.subject-id-attribute": "preferred_username",
    "authzen.resource-id-attribute": "request-uri",
    "property-mappers": "com.example.demo.authzen.mapper.UserProfilePropertyMapperFactory"
  }
}
```

### Deploying the provider

Build the JAR and copy it into Keycloak's `providers/` directory:

```bash
mvn -B package -DskipTests
cp provider/target/authzen-policy-provider-1.0.0-SNAPSHOT.jar \
   /opt/keycloak/providers/
```

## AuthZenPropertyMapper SPI

The `AuthZenPropertyMapper` SPI allows you to enrich AuthZen requests with additional context that
is not available in the access token or the Keycloak resource model. This follows the
**Policy Information Point (PIP)** pattern.

Mappers are **not** loaded automatically. Each mapper must be explicitly listed in the policy's
`property-mappers` configuration using the fully-qualified class name of its factory.

### Implementing a custom mapper

1. **Create the mapper** &mdash; implement `AuthZenPropertyMapper`:

```java
public class MyPropertyMapper implements AuthZenPropertyMapper {

    @Override
    public Map<String, Object> mapSubjectProperties(Evaluation evaluation, KeycloakSession session) {
        // Return additional subject properties, or an empty map
        return Map.of();
    }

    @Override
    public Map<String, Object> mapResourceProperties(Evaluation evaluation, KeycloakSession session) {
        // Look up additional resource context
        return Map.of("department", "engineering");
    }

    @Override
    public void close() {}
}
```

2. **Create the factory** &mdash; implement `AuthZenPropertyMapperFactory`:

```java
public class MyPropertyMapperFactory implements AuthZenPropertyMapperFactory {

    @Override
    public AuthZenPropertyMapper create(KeycloakSession session) {
        return new MyPropertyMapper();
    }

    @Override
    public String getId() {
        return "my-mapper";
    }

    @Override
    public void init(Config.Scope config) {}

    @Override
    public void postInit(KeycloakSessionFactory factory) {}

    @Override
    public void close() {}
}
```

3. **Register the SPI** &mdash; create `META-INF/services/com.example.demo.authzen.spi.AuthZenPropertyMapperFactory`:

```
com.example.myapp.MyPropertyMapperFactory
```

4. **Configure the policy** &mdash; add the factory's fully-qualified class name to the
   `property-mappers` config:

```json
"property-mappers": "com.example.myapp.MyPropertyMapperFactory"
```

Multiple mappers can be comma-separated:

```json
"property-mappers": "com.example.Mapper1Factory,com.example.Mapper2Factory"
```

### Included mapper: UserProfilePropertyMapper

The `user-profile-mapper` module provides a reference implementation that:

- Extracts the target username from the `request-uri` evaluation context attribute
- Looks up the target user in the Keycloak user store
- Adds the user's `team` attribute to the AuthZen resource properties

This enables policies like "a manager can view profiles of users on their own team" without
embedding team data in the access token.

Deploy alongside the provider:

```bash
cp user-profile-mapper/target/authzen-user-profile-mapper-1.0.0-SNAPSHOT.jar \
   /opt/keycloak/providers/
```

## Demo

### Prerequisites

- Java 17+
- Maven
- Docker & Docker Compose

### Users

| Username | Password | Team | Roles |
|---|---|---|---|
| alice | password | engineering | user |
| bob | password | marketing | user |
| charlie | password | engineering | user, manager |

### Cerbos policy rules

The demo Cerbos policy (`cerbos/policies/user_profile.yaml`) implements the following rules for
the `user_profile` resource type:

1. **Own profile** &mdash; any user can view and edit their own profile
   (`resource.id == subject.id`)
2. **Team manager** &mdash; a user with the `manager` role can view profiles of users on the same
   team

### Running the demo

```bash
# Build all modules
mvn -B package -DskipTests

# Start Cerbos, Keycloak, and the demo app
docker compose up
```

Once all services are healthy:

- **Demo app**: http://localhost:8081
- **Keycloak admin console**: http://localhost:8080 (admin / admin)

### Testing authorization

1. Open http://localhost:8081 and log in as **alice** (password: `password`).
   You are redirected to `/users/alice` &mdash; your own profile.

2. Navigate to `/users/bob`. Access is **denied** because alice is not a manager.

3. Log out and log in as **charlie** (password: `password`).
   Navigate to `/users/alice`. Access is **allowed** because charlie is a manager on the same team
   (engineering).

4. Navigate to `/users/bob`. Access is **denied** because bob is on the marketing team.

### Debugging

Remote debugging is enabled for both Keycloak (port 5006) and the Quarkus app (port 5005).

To enable trace logging for the AuthZen provider, the docker-compose file sets:

```
KC_LOG_LEVEL: INFO,com.example.demo.authzen:TRACE
```

This outputs the full AuthZen JSON request and PDP decision for each evaluation.
