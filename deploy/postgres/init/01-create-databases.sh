#!/usr/bin/env bash
# Creates one database and one login role per service (ADR-0005, option A2).
# Runs once, when the data directory is first created (docker-entrypoint-initdb.d).
# Each role can connect to its own database only. Passwords come from the
# environment (compose passes them from deploy/.env), never from this file.
set -euo pipefail

create_service_database() {
  local name="$1"
  local password="$2"

  if [ -z "$password" ]; then
    echo "ERROR: no password set for service '$name' (see deploy/.env.example)" >&2
    return 1
  fi

  psql -q -v ON_ERROR_STOP=1 -v pw="$password" --username "$POSTGRES_USER" --dbname postgres <<SQL
CREATE ROLE ${name} LOGIN PASSWORD :'pw';
CREATE DATABASE ${name} OWNER ${name};
REVOKE CONNECT ON DATABASE ${name} FROM PUBLIC;
GRANT CONNECT ON DATABASE ${name} TO ${name};
SQL
  echo "created database and role: ${name}"
}

create_service_database identity     "${IDENTITY_DB_PASSWORD:-}"
create_service_database catalog      "${CATALOG_DB_PASSWORD:-}"
create_service_database booking      "${BOOKING_DB_PASSWORD:-}"
create_service_database payment      "${PAYMENT_DB_PASSWORD:-}"
create_service_database notification "${NOTIFICATION_DB_PASSWORD:-}"
