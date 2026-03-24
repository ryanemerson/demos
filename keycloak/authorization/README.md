# Keycloak Authorization PDP Demo

Demonstrates Keycloak acting as an Authorization Policy Decision Point (PDP) with a Quarkus REST service.

## Architecture

- **Quarkus REST service** with HTML frontend using `quarkus-keycloak-authorization` and `quarkus-oidc`
- **Keycloak** with Authorization Services configured (resources, policies, permissions)

## Endpoints

| Endpoint | Authorization Rule |
|---|---|
| `/` | Public landing page |
| `/users/{username}` | Own profile OR `manager` role |
| `/teams/{teamname}` | `manager` role AND matching `team` attribute |

## Test Users

| Username | Password | Roles | Team |
|---|---|---|---|
| `alice` | `password` | `user` | `engineering` |
| `bob` | `password` | `user` | `marketing` |
| `charlie` | `password` | `user`, `manager` | `engineering` |

## Running

### Start

```bash
docker compose up --build -d
```

- **Keycloak Admin**: http://localhost:8080 (admin/admin)
- **Application**: http://localhost:8081

### Test Scenarios

1. **Login as Alice** → Redirected to `/users/alice` (own profile). Cannot access `/users/bob` (403). Cannot access `/teams/engineering` (403 - not a manager).
2. **Login as Charlie** → Redirected to `/users/charlie`. Can access `/users/alice` and `/users/bob` (manager). Can access `/teams/engineering` (manager + team match). Cannot access `/teams/marketing` (team mismatch).
3. **Login as Bob** → Can only access `/users/bob`. Cannot access teams (not a manager).

### Stop

```bash
docker compose down -v
```

## Authorization Flow

1. User accesses a protected endpoint
2. Quarkus OIDC redirects to Keycloak for SSO login
3. After login, the **Keycloak Policy Enforcer** intercepts the request
4. The enforcer sends a permission request to Keycloak's Authorization Services
5. Keycloak evaluates the configured policies (role-based) and returns a decision
6. The application applies additional fine-grained checks (username match, team attribute)
