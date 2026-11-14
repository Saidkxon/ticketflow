#!/usr/bin/env bash
# Checks the running local stack end to end. Start it first:
#   docker compose -f deploy/compose.yaml --profile app up -d --build --wait
# Usage: scripts/smoke-test.sh [web-base-url] [gateway-base-url]
set -u

WEB="${1:-http://localhost:${WEB_PORT:-18090}}"
GATEWAY="${2:-http://localhost:${GATEWAY_PORT:-18080}}"
failed=0

check() { # name, expected, actual
  if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FAIL $1 (expected '$2', got '$3')"; failed=1; fi
}
contains() { # name, text, haystack
  case "$3" in *"$2"*) echo "ok   $1" ;; *) echo "FAIL $1 (missing '$2' in: $3)"; failed=1 ;; esac
}

check "gateway: event list" 200 "$(curl -s -o /dev/null -w '%{http_code}' "$GATEWAY/api/v1/events")"
check "web: home page" 200 "$(curl -s -o /dev/null -w '%{http_code}' "$WEB/")"
contains "web: home page is the app" '<div id="root">' "$(curl -s "$WEB/")"
check "web: app screen path falls back to the app" 200 "$(curl -s -o /dev/null -w '%{http_code}' "$WEB/events/some-screen")"
check "web: event list through the proxy" 200 "$(curl -s -o /dev/null -w '%{http_code}' "$WEB/api/v1/events")"
contains "web: event list has the page shape" '"totalItems"' "$(curl -s "$WEB/api/v1/events")"
check "web: unknown API path is a JSON 404" 404 "$(curl -s -o /dev/null -w '%{http_code}' "$WEB/api/v1/nothing-here")"
contains "web: unknown API path has the stable code" 'ROUTE_NOT_FOUND' "$(curl -s "$WEB/api/v1/nothing-here")"
id=3f2b8a52-6c1e-4d7a-9b0e-5a1c2d3e4f50
contains "web: correlation ID comes back" "x-correlation-id: $id" "$(curl -s -D - -o /dev/null -H "X-Correlation-ID: $id" "$WEB/api/v1/events" | tr 'A-Z' 'a-z')"
check "health is not on the application port" 404 "$(curl -s -o /dev/null -w '%{http_code}' "$GATEWAY/actuator/health")"

exit $failed
