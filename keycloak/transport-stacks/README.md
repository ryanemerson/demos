# jdbc-ping

1.Start postgres and a single Keycloak container

```
docker compose up --scale keycloak=1
```

2. Show transport stack in logs, single cluster member etc

3. Scale cluster up to 2 Keycloak instances

```
docker compose up -d --scale keycloak=2
```

# jdbc-ping-udp

1. Modify stack in compose file to be `jdbc-ping-udp`

2. Start cluster with two Keycloak replicas and show cluster is still formed as
   expected. Show stack name in logs


```
docker compose up --scale keycloak=2
```

# tcp

1. Modify stack in compose file to be `tcp`

2. Start cluster

```
docker compose up --scale keycloak=2
```

3. Show that `tcp` stack is used in logs

4. Show that deprecation and `jdbc-ping` recommendation is logged
