# TLS Enabled

1.Start postgres and a Keycloak cluster

```
docker compose up --scale keycloak=1
```

2. Show transport stack in logs, single cluster member etc

```
INFO  [org.keycloak.quarkus.runtime.storage.infinispan.CacheManagerFactory] (main) JGroups Encryption enabled (mTLS).
```

3. Explain rotation

- The generated certificates are valid for 60 days
- Rotated every 30 days

# TLS Disabled

1. Start Keycloak with `--cache-embedded-mtls-enabled=false` argument

```
docker compose up --scale keycloak=1
```

2. No log message should appear

# TLS Custom Keystore

1. Start Keycloak with the following args:

```
--cache-embedded-mtls-key-store-file
--cache-embedded-mtls-key-store-password
--cache-embedded-mtls-trust-store-file
--cache-embedded-mtls-trust-store-password
```

```
docker compose up --scale keycloak=2
```

2. The cluster should correctly form