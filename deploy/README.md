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

Builds the web app, the gateway, the Catalog service and the Identity service into images and starts them with the database. The first build downloads Maven and npm dependencies and takes a few minutes; later builds reuse the cache.

Once, before the first start, make the development key that the Identity service uses to sign access tokens. It is saved in `deploy/keys/` (git-ignored) and is for development only:

    bash scripts/generate-dev-key.sh

Then:

    docker compose -f deploy/compose.yaml --profile app up -d --build --wait
    bash scripts/smoke-test.sh                                    # end-to-end checks of the running stack
    docker compose -f deploy/compose.yaml --profile app down   # stop, keep the data

Open http://localhost:18090 in the browser (set `WEB_PORT` in `deploy/.env` to change it). The web container serves the app and forwards `/api` to the gateway, so the browser talks to one origin (ADR-0013, G10). The gateway is also published on `127.0.0.1:18080` for `curl` and tests (`GATEWAY_PORT` changes it). The Catalog service and the health ports stay inside the container network (ADR-0008, ADR-0013). Running a service from the IDE and the same service in a container at the same time is not supported: they would compete for ports.

This is the local setup. Which component serves the app in production is decided at the deployment gate (ADR-0013, G10).

## The message broker (RabbitMQ)

The broker (ADR-0003) starts together with the applications (`--profile app`), or alone with `docker compose -f deploy/compose.yaml --profile broker up -d rabbitmq` when you run services from the IDE. Add its password to `deploy/.env` once:

    echo "RABBITMQ_PASSWORD=$(openssl rand -hex 12)" >> deploy/.env

The broker listens on `127.0.0.1:15673` (`RABBITMQ_PORT` changes it) and its management page is at http://localhost:15674 (`RABBITMQ_MANAGEMENT_PORT`). Sign in with user `ticketflow` and that password. Queues and messages are kept in the `rabbitmq-data` volume.

Catalog does not need the broker to start or to answer requests. It writes events to its own outbox table, and a relay publishes them to the exchange `ticketflow.events` (see `contracts/events/README.md`). While nobody has bound a queue for an event, or while the broker is down, the events wait in the outbox and are sent as soon as they can be received.

### When an event does not go on sale

Booking builds the seats of a newly published event from a message. If Catalog is unreachable for longer than about two minutes, or the message is faulty, Booking parks it in the queue `booking.catalog.layout-published.dlq` instead of losing it. To put the event on sale after the cause is fixed:

1. Open the management page (http://localhost:15674), then Queues and Streams, then `booking.catalog.layout-published.dlq`, then Get messages. Copy the payload (the `failure` header says what went wrong).
2. Open the exchange `ticketflow.events`, then Publish message. Routing key `catalog.layout-published`, paste the payload, and publish.
3. Back in the dead-letter queue, click Purge Messages.

To see what Booking has built for an event: `select count(*) from event_seats where event_id = '<the event ID>'` in the `booking` database.

## Signing keys for access tokens

The Identity service signs access tokens (JWT) with an RSA key that it reads from a directory (ADR-0004). Every file `NAME.pem` in that directory is one key with the ID `NAME`; the key named by `IDENTITY_JWT_SIGNING_KEY_ID` signs new tokens, and all of them are published (public part only) at `/.well-known/jwks.json` so tokens can be checked. The format is PKCS#8 PEM (`-----BEGIN PRIVATE KEY-----`), at least 2048 bits. A missing directory, key or key ID stops the service at startup with a message that says what is wrong.

- **Development and CI:** `bash scripts/generate-dev-key.sh` creates `deploy/keys/dev-1.pem`. The `deploy/keys/` folder is git-ignored and excluded from images. Never use a development key anywhere else.
- **Rotating a key:** add the new file (for example `2027-01.pem`), set `IDENTITY_JWT_SIGNING_KEY_ID` to its ID, and restart Identity. Keep the old file for at least the access token lifetime (15 minutes) so tokens it signed stay valid, then delete it. The production procedure is written with the deployment runbook (ADR-0014).

## The first Admin

Nobody becomes an Admin by registering. The first Admin is made like this:

1. Register the account normally (through the web app or `POST /api/v1/auth/register`). If the answer is 409, the email is already taken by someone else: use another one.
2. Put the same email in `deploy/.env`: `IDENTITY_BOOTSTRAP_ADMIN_EMAIL=you@example.com`.
3. Restart Identity: `docker compose -f deploy/compose.yaml --profile app up -d identity`. The log shows `bootstrap admin: the configured user is an Admin now`.
4. Log in again (an old token does not have the role). From then on this Admin gives roles to others with `PUT /api/v1/users/{id}/roles`.

The setting does nothing when the user is already an Admin, so it can stay. If the log says no user is registered with that email, register first and restart.

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

The broker uses 15673 (AMQP) and 15674 (management page), also high ports for the same reason.

To change a port without editing code, set `SERVER_PORT` or `MANAGEMENT_SERVER_PORT` in the environment. To see which ports Windows reserves: `netsh interface ipv4 show excludedportrange protocol=tcp`.
