# TicketFlow web app

React, TypeScript and Vite single-page app (ADR-0010). In the browser it talks to one origin only: the dev server forwards `/api` to the API gateway (ADR-0013, rule G10), so no CORS is configured.

## Run it (development)

1. Start the backend, from the repository root, one command per terminal window (the database is described in [deploy/README.md](../deploy/README.md)):

```
docker compose -f deploy/compose.yaml up -d
java -jar services/catalog-service/target/catalog-service-0.1.0-SNAPSHOT.jar --spring.profiles.active=local
java -jar services/api-gateway/target/api-gateway-0.1.0-SNAPSHOT.jar
```

   Build the jars first with `./mvnw -B verify` if `target/` is empty.

2. From this folder (`web/`):

```
npm ci
npm run dev
```

Open http://localhost:5173. The page lists the published events from `GET /api/v1/events`. To sign in and register, Identity must run too (`java -jar services/identity-service/target/identity-service-0.1.0-SNAPSHOT.jar --spring.profiles.active=local`, with a development signing key, see [deploy/README.md](../deploy/README.md)), and the gateway must be started after it. The easiest way is the whole stack in containers, with the web app on http://localhost:18090.

## Sign in

Pages: `/login`, `/register`, and `/account` (only for a signed-in user; anybody else is sent to `/login` and brought back afterwards). The rules of ADR-0004 in the browser:

- The access token is kept **in memory only** (never localStorage or sessionStorage), so a page reload loses it. The HttpOnly refresh cookie then brings the session back: the app calls `POST /api/v1/auth/refresh` once when it loads.
- **Refresh runs in exactly one place** (`src/auth/sessionStore.ts`). The server treats a refresh token used twice as theft and ends the whole session, so every caller shares the one refresh call that is in flight. Do not add another call to `/auth/refresh` anywhere else.
- Calls that need a token go through `authorizedFetch`: on a 401 it refreshes once, retries once, and signs the user out if that fails too.
- The app refreshes a minute before the access token expires. Any failed refresh ends the session.
- The browser must accept the `Secure` refresh cookie. Chrome, Edge and Firefox accept it on `http://localhost`; Safari does not, and neither does an address like `http://192.168.x.x`. Use https or localhost.
- The pages show messages for known error `code`s and a reference (the correlation ID) when the server gave one.

## Location question

After registration and every login the app shows the location question (ADR-0015) before any page. The person must choose **Full** (history kept 30 days) or **Minimum** (5 days); there is no "No", and the browser must allow location access as well, otherwise the app stays blocked behind an explanation with a "Try again" button. The account page switches between Full and Minimum. While the app is open and permitted, `src/location/usePositionSender.ts` sends a point when the person has moved about 200 m and a minute has passed since the last one.

The one switch that brings a "Not now" link back is the build setting `VITE_LOCATION_ALLOW_NOT_NOW=true` (default `false`; `src/location/config.ts`). With `npm run dev`, put it in `web/.env.local`. For the containers, put it in `deploy/.env` and rebuild the web image. The consent text and its version are in `src/location/consentText.tsx`; raise the version there and in Identity's `ticketflow.identity.location.consent-text-version` together to ask everybody again. Browsers only offer location on `https://` addresses and on `localhost`.

## Administrator map

`/admin/locations` (ADMIN role only, link "Locations" in the header) shows the latest position of every tracked user on a map with their names, and a table; "History" shows one user's trail. The map is Leaflet with OpenStreetMap tiles: free, no account and no key. Only `src/admin/maps/` knows the map library (`loadMaps.ts` is the adapter, loaded on demand), so another provider can replace it later. The map tiles come straight from `tile.openstreetmap.org`, so the administrator's browser tells OpenStreetMap which area is being viewed; OpenStreetMap's tile policy allows normal administrator use but not heavy traffic, and the attribution on the map must stay. Every read of the data is written to an access log on the server, so the page reads on request only: the first visit, the Refresh button and a click on History, never by a timer.

## Checks (the same ones CI runs)

```
npm run typecheck
npm run lint
npm test
npm run build
```

## API types

The types in `src/api/catalog.schema.d.ts` and `src/api/identity.schema.d.ts` are generated from [contracts/catalog.openapi.yaml](../contracts/catalog.openapi.yaml) and [contracts/identity.openapi.yaml](../contracts/identity.openapi.yaml), so the app cannot silently drift from the contract (ADR-0009, part E). After a contract change, run:

```
npm run generate:api
```

and commit the result. CI fails if the committed file is out of date.
