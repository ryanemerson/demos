# Keycloak Authorization PDP Demo

Demonstrates Keycloak acting as an **Authorization Policy Decision Point (PDP)** with a Quarkus REST service.
All authorization decisions are made centrally by Keycloak's Authorization Services — the application contains
no authorization logic.

## Architecture

```
Browser ──► Quarkus App ──► Keycloak Policy Enforcer ──► Keycloak Authorization Services
                │                                               │
                │  (data only)                                  │  (authorization decisions)
                ▼                                               ▼
         Keycloak Admin Client                        JavaScript Policy + Role Policies
```

- **Authorization** — The Keycloak Policy Enforcer intercepts requests and delegates authorization to Keycloak's
  Authorization Services. A server-side JavaScript policy (`own-user-or-same-team-manager.js`) checks own-user
  access and same-team manager access using `$evaluation.getRealm()` to query user attributes directly from the
  Keycloak user store.
- **Data retrieval** — The Keycloak Admin Client is used only to fetch display data (listing team members for the
  Reports section, looking up another user's roles). It is never used for authorization decisions.

## Endpoints

| Endpoint             | Authorization Rule                                              |
|----------------------|-----------------------------------------------------------------|
| `/`                  | Public landing page                                             |
| `/users`             | Authenticated — redirects to `/users/{username}`                |
| `/users/{username}`  | Own profile OR `manager` role with same `team` attribute        |

Managers also see a **Reports** section listing users in their team.

## Demo Users

| Username  | Password   | Roles             | Team          |
|-----------|------------|-------------------|---------------|
| `alice`   | `password` | `user`            | `engineering` |
| `bob`     | `password` | `user`            | `marketing`   |
| `charlie` | `password` | `user`, `manager` | `engineering` |

## Running

### Start

```bash
docker compose up --build -d
```

- **Keycloak Admin**: http://localhost:8080 (admin/admin)
- **Application**: http://localhost:8081

### Test Scenarios

1. **Login as Alice** — Redirected to `/users/alice` (own profile). Cannot access `/users/bob` (403).
2. **Login as Charlie** — Redirected to `/users/charlie`. Can access `/users/alice` (same team, manager).
   Cannot access `/users/bob` (different team, 403). Sees a Reports section listing `alice`.
3. **Login as Bob** — Can only access `/users/bob`. Cannot access other users' profiles (not a manager).

### Stop

```bash
docker compose down -v
```

## Authorization Flow

1. User accesses a protected endpoint
2. Quarkus OIDC redirects to Keycloak for SSO login
3. After login, the **Keycloak Policy Enforcer** intercepts the request
4. The enforcer sends a permission request to Keycloak's Authorization Services, including the request URI
   as a claim via Claim Information Points
5. Keycloak evaluates the configured policies server-side:
   - **Authenticated Users Policy** — verifies the user has the `user` role
   - **Own User or Same Team Manager Policy** — a JavaScript policy that extracts the target username from
     the request URI, then grants access if it matches the authenticated user or if the user is a manager
     in the same team (using `$evaluation.getRealm().getUserAttributes()` to query user attributes)
6. Keycloak returns a permit/deny decision — the application contains no authorization checks

## Keycloak Authorization Configuration

### Resources

| Resource             | URI          | Description                                    |
|----------------------|--------------|------------------------------------------------|
| User List Resource   | `/users`     | Redirect endpoint — any authenticated user     |
| User Resource        | `/users/*`   | User profiles — evaluated by JS policy         |

### Policies

| Policy                               | Type       | Description                                        |
|---------------------------------------|------------|----------------------------------------------------|
| Authenticated Users Policy            | Role       | Requires the `user` realm role                     |
| Manager Role Policy                   | Role       | Requires the `manager` realm role                  |
| Own User or Same Team Manager Policy  | JavaScript | Grants if own profile, or manager in the same team |

### Permissions

| Permission               | Resource             | Policies Applied                                             |
|--------------------------|----------------------|--------------------------------------------------------------|
| User List Permission     | User List Resource   | Authenticated Users Policy                                   |
| User Resource Permission | User Resource        | Authenticated Users Policy AND Own User or Same Team Manager |

### JavaScript Policy Deployment

Keycloak disables inline script upload by default. The JavaScript policy is deployed as a JAR provider:

```
keycloak/scripts/
├── META-INF/keycloak-scripts.json      # JAR descriptor registering the policy provider
└── own-user-or-same-team-manager.js    # Policy script
```

A custom Keycloak Dockerfile packages the scripts into a JAR and copies it to `/opt/keycloak/providers/`.
The `KC_FEATURES=scripts` environment variable enables JavaScript policy support.

### Claim Information Points

The Policy Enforcer is configured to pass `{request.relativePath}` as the `request-uri` claim to Keycloak,
allowing the JavaScript policy to extract the target username from the URI path.

### Protocol Mappers

- **team-mapper** — Maps the user's `team` attribute to the `team` token claim
- **realm-roles-mapper** — Maps realm roles to `realm_access.roles` token claim

## Project Structure

```
├── docker-compose.yml                          # Keycloak + Quarkus app
├── Dockerfile                                  # Quarkus app (multi-stage Maven build)
├── keycloak/
│   ├── Dockerfile                              # Custom Keycloak image with JS policy JAR
│   ├── demo-realm.json                         # Realm config (users, roles, authorization)
│   └── scripts/
│       ├── META-INF/keycloak-scripts.json      # JAR descriptor for JS policy provider
│       └── own-user-or-same-team-manager.js    # Authorization policy script
└── src/main/
    ├── java/.../authorization/
    │   ├── IndexResource.java                  # Public landing page
    │   └── UserResource.java                   # User profile endpoint
    └── resources/
        ├── application.properties              # OIDC, Admin Client, Policy Enforcer config
        └── templates/
            ├── IndexResource/index.html        # Landing page template
            └── UserResource/user.html          # User profile template
```

## Key Technologies

- **Quarkus** with `quarkus-oidc`, `quarkus-keycloak-authorization`, `quarkus-keycloak-admin-resteasy-client`
- **Keycloak** with Authorization Services and JavaScript policies
- **OIDC Authorization Code Flow** (`web-app` application type)
- **Qute** templates for server-side HTML rendering
