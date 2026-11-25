#!/usr/bin/env bash
# Checks the running local stack end to end. Start it first:
#   docker compose -f deploy/compose.yaml --profile app up -d --build --wait
# Usage: scripts/smoke-test.sh [web-base-url] [gateway-base-url]
# The hold path near the end needs "docker compose" (it gives a test user the ORGANIZER role) and leaves one published
# event in 2099 behind; SMOKE_SKIP_HOLD_PATH=1 skips it. It needs a minute between two runs (sign-in rate limit).
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
# One event by ID (PR 13a): public, answered by Catalog; an unknown ID is Catalog's own 404 (code NOT_FOUND).
one_event="$(curl -s -w '\n%{http_code}' "$WEB/api/v1/events/3f2b8a52-6c1e-4d7a-9b0e-5a1c2d3e4f50")"
check "web: an unknown event is a 404" 404 "$(printf '%s' "$one_event" | tail -1)"
contains "web: the 404 comes from Catalog" '"code":"NOT_FOUND"' "$one_event"
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
# Booking (ADR-0013): its paths need a token at the gateway; with a token the request reaches Booking. A booking that
# does not exist (or is somebody else's) is Booking's own 404 (code NOT_FOUND; the gateway's own 404 has ROUTE_NOT_FOUND).
check "bookings: without a token is refused" 401 "$(curl -s -o /dev/null -w '%{http_code}' "$WEB/api/v1/bookings/3f2b8a52-6c1e-4d7a-9b0e-5a1c2d3e4f50")"
booking_answer="$(curl -s -w '\n%{http_code}' -H "Authorization: Bearer $access" "$WEB/api/v1/bookings/3f2b8a52-6c1e-4d7a-9b0e-5a1c2d3e4f50")"
check "bookings: with a token the request reaches Booking" 404 "$(printf '%s' "$booking_answer" | tail -1)"
contains "bookings: the 404 comes from Booking" '"code":"NOT_FOUND"' "$booking_answer"
# My bookings and releasing a hold (PR 12): a new customer has none; releasing an unknown booking is a 404 from Booking.
my_bookings="$(curl -s -w '\n%{http_code}' -H "Authorization: Bearer $access" "$WEB/api/v1/bookings")"
check "bookings: the list of a customer" 200 "$(printf '%s' "$my_bookings" | tail -1)"
contains "bookings: the list is a page" '"totalItems":' "$my_bookings"
release_answer="$(curl -s -w '\n%{http_code}' -X POST -H "Authorization: Bearer $access" "$WEB/api/v1/bookings/3f2b8a52-6c1e-4d7a-9b0e-5a1c2d3e4f50/release")"
check "bookings: releasing an unknown booking" 404 "$(printf '%s' "$release_answer" | tail -1)"
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
# The whole hold path (PR 16). A second user is needed (to be refused a held seat) and an organizer is needed (to
# publish an event with seats). One account does both: a user whose ORGANIZER role is written straight into the
# Identity database of this test stack, because the first-admin bootstrap needs an Identity restart (ADR-0004). The
# role is read when a token is made, so the user logs in again afterwards. Set SMOKE_SKIP_HOLD_PATH=1 to skip this
# part: it needs "docker compose" and leaves one published event ("Smoke test event ...", in 2099) behind.
# The gateway allows few sign-in calls a minute from one address (GATEWAY_AUTH_LIMIT_PER_MINUTE, 10 by default), so the
# whole script uses 8 of them: wait a minute before running it again.
new_uuid() { printf '%04x%04x-%04x-4%03x-a%03x-%04x%04x%04x\n' $RANDOM $RANDOM $RANDOM $((RANDOM % 4096)) $((RANDOM % 4096)) $RANDOM $RANDOM $RANDOM; }
etag_of() { printf '%s\n' "$1" | grep -i '^etag:' | head -1 | sed 's/^[^:]*: *//'; }
id_of() { printf '%s' "$1" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4; }
bearer() { printf 'Authorization: Bearer %s' "$1"; }
json_call() { # token method path [extra curl args...]: the answer with headers, then the body, CR removed
  tok="$1"; method="$2"; path="$3"; shift 3
  curl -s -D - -X "$method" -H "$(bearer "$tok")" -H 'Content-Type: application/json' "$@" "$WEB$path" | tr -d '\r'
}
hold_call() { # token idempotency-key seat-id... : hold the seats of the smoke event
  tok="$1"; key="$2"; shift 2
  ids=""; for seat in "$@"; do ids="${ids:+$ids,}\"$seat\""; done
  json_call "$tok" POST /api/v1/bookings -H "Idempotency-Key: $key" -d "{\"eventId\":\"$event_id\",\"seatIds\":[$ids]}"
}

if [ "${SMOKE_SKIP_HOLD_PATH:-0}" = 1 ]; then
  echo "skip the whole hold path (SMOKE_SKIP_HOLD_PATH=1)"
else
  COMPOSE_FILE="${COMPOSE_FILE:-$(dirname "$0")/../deploy/compose.yaml}"
  org_email="smoke-org-$(date +%s)-$RANDOM@example.com"
  org_password="smoke test password 1"
  check "organizer: register" 201 "$(status_of "$(auth register -d "{\"email\":\"$org_email\",\"password\":\"$org_password\",\"displayName\":\"Smoke Organizer\"}")")"
  org_login="$(auth login -d "{\"email\":\"$org_email\",\"password\":\"$org_password\"}")"
  check "organizer: login" 200 "$(status_of "$org_login")"
  org_first_token="$(printf '%s' "$org_login" | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)"
  org_id="$(id_of "$(curl -s -H "$(bearer "$org_first_token")" "$WEB/api/v1/users/me")")"
  granted="$(docker compose -f "$COMPOSE_FILE" exec -T postgres psql -U identity -d identity -c "insert into user_roles (user_id, role) values ('$org_id', 'ORGANIZER') on conflict do nothing" 2>&1)"
  contains "organizer: the role is written in the test database" "INSERT 0 1" "$granted"
  org="$(auth login -d "{\"email\":\"$org_email\",\"password\":\"$org_password\"}" | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)"

  created="$(json_call "$org" POST /api/v1/events -d "{\"title\":\"Smoke test event $(date +%s)\",\"startsAt\":\"2099-01-01T18:00:00Z\",\"venueName\":\"Smoke Hall\",\"city\":\"Tashkent\"}")"
  check "events: an organizer creates a draft" 201 "$(status_of "$created")"
  event_id="$(id_of "$(printf '%s\n' "$created" | tail -1)")"
  saved="$(json_call "$org" PUT "/api/v1/events/$event_id/layout" -H "If-Match: $(etag_of "$created")" -d '{"currency":"UZS","priceTiers":[{"name":"Standard","priceMinor":100000}],"sections":[{"name":"Hall","tier":"Standard","rows":1,"seatsPerRow":6}]}')"
  check "events: the layout is saved" 200 "$(status_of "$saved")"
  published="$(json_call "$org" POST "/api/v1/events/$event_id/publish" -H "If-Match: $(etag_of "$saved")")"
  check "events: the event is published" 200 "$(status_of "$published")"

  # Booking gets its seats from the "layout published" message (ADR-0012): wait until it has them.
  provisioned=no
  for _ in $(seq 1 30); do
    if [ "$(curl -s -o /dev/null -w '%{http_code}' "$WEB/api/v1/events/$event_id/availability")" = 200 ]; then provisioned=yes; break; fi
    sleep 1
  done
  check "availability: Booking has the seats after publishing" yes "$provisioned"
  seats="$(curl -s "$WEB/api/v1/events/$event_id/layout" | grep -o '"id":"[^"]*","number":[0-9]*' | cut -d'"' -f4)"
  check "layout: six seats" 6 "$(printf '%s\n' "$seats" | grep -c .)"
  seat1="$(printf '%s\n' "$seats" | sed -n 1p)"; seat2="$(printf '%s\n' "$seats" | sed -n 2p)"; seat3="$(printf '%s\n' "$seats" | sed -n 3p)"

  key="$(new_uuid)"
  held="$(hold_call "$access" "$key" "$seat1" "$seat2")"
  check "holds: a customer holds two seats" 201 "$(status_of "$held")"
  contains "holds: the booking is HELD" '"status":"HELD"' "$held"
  booking_id="$(id_of "$(printf '%s\n' "$held" | tail -1)")"
  replay="$(hold_call "$access" "$key" "$seat1" "$seat2")"
  contains "holds: the same key answers with the same booking" "\"id\":\"$booking_id\"" "$replay"
  more="$(hold_call "$access" "$(new_uuid)" "$seat3")"
  check "holds: a second hold of the same event is refused" 409 "$(status_of "$more")"
  contains "holds: the refusal is ACTIVE_HOLD_EXISTS" '"code":"ACTIVE_HOLD_EXISTS"' "$more"
  taken="$(hold_call "$org" "$(new_uuid)" "$seat1")"
  check "holds: another user cannot take a held seat" 409 "$(status_of "$taken")"
  contains "holds: the refusal is SEATS_UNAVAILABLE" '"code":"SEATS_UNAVAILABLE"' "$taken"
  contains "holds: the refusal names the seat" "$seat1" "$taken"
  # The list of unavailable seats is reused for about two seconds (ADR-0011), so wait for it.
  listed=no
  for _ in $(seq 1 10); do
    case "$(curl -s "$WEB/api/v1/events/$event_id/availability")" in *"$seat1"*) listed=yes; break ;; esac
    sleep 1
  done
  check "availability: a held seat is listed as unavailable" yes "$listed"
  contains "bookings: the hold is in the list of its customer" "$booking_id" "$(curl -s -H "$(bearer "$access")" "$WEB/api/v1/bookings")"
  check "release: another user cannot release it" 404 "$(status_of "$(json_call "$org" POST "/api/v1/bookings/$booking_id/release")")"
  released="$(json_call "$access" POST "/api/v1/bookings/$booking_id/release")"
  check "release: the customer releases the hold" 200 "$(status_of "$released")"
  contains "release: the booking is CANCELLED" '"status":"CANCELLED"' "$released"
  check "release: repeating it is fine" 200 "$(status_of "$(json_call "$access" POST "/api/v1/bookings/$booking_id/release")")"
  check "holds: a released seat can be held by another user" 201 "$(status_of "$(hold_call "$org" "$(new_uuid)" "$seat1")")"
fi

first="$(cookie_of "$login")"
refreshed="$(auth refresh -H "Cookie: refresh_token=$first")"
check "auth: refresh" 200 "$(status_of "$refreshed")"
second="$(cookie_of "$refreshed")"
check "auth: refresh rotates the token" yes "$([ -n "$second" ] && [ "$second" != "$first" ] && echo yes || echo no)"
check "auth: logout" 204 "$(status_of "$(auth logout -H "Cookie: refresh_token=$second")")"
check "auth: refresh after logout is refused" 401 "$(status_of "$(auth refresh -H "Cookie: refresh_token=$second")")"

exit $failed
