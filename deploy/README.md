# Local development environment

Starts PostgreSQL 18 with one database and one login role per service (ADR-0005).

## First time

From the repository root, create `deploy/.env` with random passwords (it is git-ignored):

    while IFS= read -r l; do case "$l" in *=change-me) echo "${l%change-me}$(openssl rand -hex 12)";; *) echo "$l";; esac; done < deploy/.env.example > deploy/.env

## Everyday commands (from the repository root)

    docker compose -f deploy/compose.yaml up -d --wait     # start and wait until healthy
    docker compose -f deploy/compose.yaml down             # stop, keep the data
    docker compose -f deploy/compose.yaml down -v          # stop and DELETE all data

The database init script runs only when the data volume is empty. To run it again, use `down -v` first.

PostgreSQL listens on `127.0.0.1:5432` only. If that port is already in use on your computer, stop the other PostgreSQL first.

If the port is blocked or already in use (Windows can reserve ports), set POSTGRES_PORT in deploy/.env to a free port.

## Run everything in containers

Builds the web app, the gateway and the Catalog service into images and starts them with the database. The first build downloads Maven and npm dependencies and takes a few minutes; later builds reuse the cache.

    docker compose -f deploy/compose.yaml --profile app up -d --build --wait
    bash scripts/smoke-test.sh                                    # end-to-end checks of the running stack
    docker compose -f deploy/compose.yaml --profile app down   # stop, keep the data

Open http://localhost:18090 in the browser (set `WEB_PORT` in `deploy/.env` to change it). The web container serves the app and forwards `/api` to the gateway, so the browser talks to one origin (ADR-0013, G10). The gateway is also published on `127.0.0.1:18080` for `curl` and tests (`GATEWAY_PORT` changes it). The Catalog service and the health ports stay inside the container network (ADR-0008, ADR-0013). Running a service from the IDE and the same service in a container at the same time is not supported: they would compete for ports.

This is the local setup. Which component serves the app in production is decided at the deployment gate (ADR-0013, G10).

## Local ports

Services use high ports because Windows reserves some low ranges (for example 8081-8180, and the ranges change after restarts). Each service has an application port and a separate health port that the gateway never routes (ADR-0008, ADR-0013).

| Service | App port | Health port |
|---|---|---|
| api-gateway | 18080 | 19080 |
| catalog-service | 18081 | 19081 |
| identity-service | 18082 | 19082 |
| booking-service | 18083 | 19083 |
| payment-service | 18084 | 19084 |
| notification-service | 18085 | 19085 |

To change a port without editing code, set `SERVER_PORT` or `MANAGEMENT_SERVER_PORT` in the environment. To see which ports Windows reserves: `netsh interface ipv4 show excludedportrange protocol=tcp`.
