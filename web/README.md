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

Open http://localhost:5173. The page lists the published events from `GET /api/v1/events`.

## Checks (the same ones CI runs)

```
npm run typecheck
npm run lint
npm test
npm run build
```

## API types

The types in `src/api/catalog.schema.d.ts` are generated from [contracts/catalog.openapi.yaml](../contracts/catalog.openapi.yaml), so the app cannot silently drift from the contract (ADR-0009, part E). After a contract change, run:

```
npm run generate:api
```

and commit the result. CI fails if the committed file is out of date.
