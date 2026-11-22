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
check "web: event search through the proxy" 200 "$(curl -s -o /dev/null -w '%{http_code}' "$WEB/api/v1/events?q=jazz&city=Tashkent&nearLatitude=41.3&nearLongitude=69.2&radiusKm=25")"
check "web: a bad search value is refused" 400 "$(curl -s -o /dev/null -w '%{http_code}' "$WEB/api/v1/events?nearLatitude=99&nearLongitude=69.2")"
check "web: unknown API path is a JSON 404" 404 "$(curl -s -o /dev/null -w '%{http_code}' "$WEB/api/v1/nothing-here")"
contains "web: unknown API path has the stable code" 'ROUTE_NOT_FOUND' "$(curl -s "$WEB/api/v1/nothing-here")"
id=3f2b8a52-6c1e-4d7a-9b0e-5a1c2d3e4f50
contains "web: correlation ID comes back" "x-correlation-id: $id" "$(curl -s -D - -o /dev/null -H "X-Correlation-ID: $id" "$WEB/api/v1/events" | tr 'A-Z' 'a-z')"
check "health is not on the application port" 404 "$(curl -s -o /dev/null -w '%{http_code}' "$GATEWAY/actuator/health")"

# Sign-in through the web origin (ADR-0004, ADR-0013): register, log in, refresh, log out.
# The cookie is sent by hand, because curl does not send a Secure cookie over plain http.
ORIGIN="$WEB"
email="smoke-$(date +%s)-$RANDOM@example.com"
register_json="{\"email\":\"$email\",\"password\":\"smoke test password 1\",\"displayName\":\"Smoke Test\"}"
login_json="{\"email\":\"$email\",\"password\":\"smoke test password 1\"}"
auth() { # path [extra curl args...]: POST to the auth API as the web app would, with headers shown
  path="$1"; shift
  curl -s -D - -X POST -H "Origin: $ORIGIN" -H 'X-TicketFlow-Client: web' -H 'Content-Type: application/json' "$@" "$WEB/api/v1/auth/$path" | tr -d '\r'
}
cookie_of() { printf '%s\n' "$1" | grep -i '^set-cookie: refresh_token=' | sed 's/^[^=]*=//; s/;.*//'; }
status_of() { printf '%s\n' "$1" | head -1 | cut -d' ' -f2; }

registered="$(auth register -d "$register_json")"
check "auth: register" 201 "$(status_of "$registered")"
login="$(auth login -d "$login_json")"
check "auth: login" 200 "$(status_of "$login")"
cookie_line="$(printf '%s\n' "$login" | grep -i '^set-cookie:' | tr 'A-Z' 'a-z')"
contains "auth: login cookie is SameSite=Strict" 'samesite=strict' "$cookie_line"
contains "auth: login cookie is HttpOnly" 'httponly' "$cookie_line"
contains "auth: login returns an access token" '"accessToken"' "$login"
access="$(printf '%s' "$login" | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)"
check "auth: users/me without a token is refused" 401 "$(curl -s -o /dev/null -w '%{http_code}' "$WEB/api/v1/users/me")"
me="$(curl -s -w '\n%{http_code}' -H "Authorization: Bearer $access" "$WEB/api/v1/users/me")"
check "auth: users/me with the token" 200 "$(printf '%s' "$me" | tail -1)"
contains "auth: users/me is the registered user" "$email" "$me"
check "auth: a customer may not list users" 403 "$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $access" "$WEB/api/v1/users")"
# Organizer endpoints (ADR-0004): a customer cannot create events or list "my events"; no token is refused.
event_json='{"title":"Smoke","startsAt":"2099-01-01T18:00:00Z","venueName":"Hall","city":"Tashkent"}'
check "events: create without a token is refused" 401 "$(curl -s -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' -d "$event_json" "$WEB/api/v1/events")"
check "events: a customer may not create an event" 403 "$(curl -s -o /dev/null -w '%{http_code}' -X POST -H "Authorization: Bearer $access" -H 'Content-Type: application/json' -d "$event_json" "$WEB/api/v1/events")"
check "events: a customer may not list my events" 403 "$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $access" "$WEB/api/v1/events/mine")"
# Booking (ADR-0013): its paths need a token at the gateway; with a token the request reaches Booking. Reading a booking
# does not exist yet, so Booking answers with its own 404 (code NOT_FOUND; the gateway's own 404 has ROUTE_NOT_FOUND).
check "bookings: without a token is refused" 401 "$(curl -s -o /dev/null -w '%{http_code}' "$WEB/api/v1/bookings/3f2b8a52-6c1e-4d7a-9b0e-5a1c2d3e4f50")"
booking_answer="$(curl -s -w '\n%{http_code}' -H "Authorization: Bearer $access" "$WEB/api/v1/bookings/3f2b8a52-6c1e-4d7a-9b0e-5a1c2d3e4f50")"
check "bookings: with a token the request reaches Booking" 404 "$(printf '%s' "$booking_answer" | tail -1)"
contains "bookings: the 404 comes from Booking" '"code":"NOT_FOUND"' "$booking_answer"
# Seat availability (ADR-0011): public, answered by Booking even though its path is below /api/v1/events (Catalog's).
# An event without seats in Booking is "not on sale"; Catalog's own 404 for an unknown event has the code NOT_FOUND.
unknown_event=3f2b8a52-6c1e-4d7a-9b0e-5a1c2d3e4f50
availability="$(curl -s -w '\n%{http_code}' "$WEB/api/v1/events/$unknown_event/availability")"
check "availability: public, without a token" 404 "$(printf '%s' "$availability" | tail -1)"
contains "availability: answered by Booking" '"code":"EVENT_NOT_ON_SALE"' "$availability"
# Holding seats (ADR-0012): the path is Booking's, needs a token and an Idempotency-Key. An event without seats is "not
# on sale", which proves that a real access token from Identity is accepted by Booking and the request body arrives.
hold_body="{\"eventId\":\"$unknown_event\",\"seatIds\":[\"$unknown_event\"]}"
hold_answer="$(curl -s -w '\n%{http_code}' -X POST -H "Authorization: Bearer $access" -H 'Content-Type: application/json' -H "Idempotency-Key: $unknown_event" -d "$hold_body" "$WEB/api/v1/bookings")"
check "holds: an event that is not on sale" 404 "$(printf '%s' "$hold_answer" | tail -1)"
contains "holds: the refusal comes from Booking" '"code":"EVENT_NOT_ON_SALE"' "$hold_answer"
first="$(cookie_of "$login")"
refreshed="$(auth refresh -H "Cookie: refresh_token=$first")"
check "auth: refresh" 200 "$(status_of "$refreshed")"
second="$(cookie_of "$refreshed")"
check "auth: refresh rotates the token" yes "$([ -n "$second" ] && [ "$second" != "$first" ] && echo yes || echo no)"
check "auth: logout" 204 "$(status_of "$(auth logout -H "Cookie: refresh_token=$second")")"
check "auth: refresh after logout is refused" 401 "$(status_of "$(auth refresh -H "Cookie: refresh_token=$second")")"

exit $failed
