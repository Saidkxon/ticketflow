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
