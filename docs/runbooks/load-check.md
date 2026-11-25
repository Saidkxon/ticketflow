# Runbook: load check of availability and holds

The script `scripts/load-check.mjs` measures the two numbers that the plan promises (`docs/plans/phase-4b-booking.md`, Phase 5 row) and checks that a contest for the same seats has one winner:

| What | Target |
|---|---|
| Seat availability, 50 browsers polling the seat map | p95 under 200 ms |
| Holds, 50 customers holding two seats each at the same moment | p95 under 500 ms |
| 50 customers asking for the same two seats | exactly one hold succeeds (NFR-1) |

It also fails on any server error or timeout, and on any call that the gateway refused because of its rate limit.

## Before you run it

1. **The stack is running:** `docker compose -f deploy/compose.yaml --profile app up -d --build --wait`.
2. **Raise the gateway limits.** The defaults (10 sign-in calls, 100 other calls and 60 availability polls a minute from one address, and 100 a minute per user) are made for people. 50 pollers alone make about 1,000 availability calls a minute from one address. Recreate the gateway with all four limits raised:

   ```
   GATEWAY_AUTH_LIMIT_PER_MINUTE=100000 GATEWAY_DEFAULT_LIMIT_PER_MINUTE=100000 GATEWAY_AVAILABILITY_LIMIT_PER_MINUTE=100000 GATEWAY_USER_LIMIT_PER_MINUTE=100000 docker compose -f deploy/compose.yaml --profile app up -d --wait gateway
   ```

   Do this on a development machine only. When the check is done, put the defaults back by running `docker compose -f deploy/compose.yaml --profile app up -d --wait gateway` without those variables. `--wait` matters: while the gateway restarts the web server answers 502, and the load check would stop at its first call.
3. **An organizer account** that exists on this stack. Use a throwaway one, never your own: the smoke test makes one every run (`smoke-org-<time>-<n>@example.com`, password `smoke test password 1`, see `scripts/smoke-test.sh`). Take the newest from the database:

   ```
   export LOAD_ORGANIZER_EMAIL="$(docker compose -f deploy/compose.yaml exec -T postgres psql -U identity -d identity -t -A -c "select email from users where email like 'smoke-org-%' order by email desc limit 1;" | tr -d '\r')"
   ```

   ```
   export LOAD_ORGANIZER_PASSWORD='smoke test password 1'
   ```

   Do not type a real password into a command: it ends up in the shell history. If you must use a real account, use `read -rs LOAD_ORGANIZER_PASSWORD && export LOAD_ORGANIZER_PASSWORD`, which asks for the password without showing it, and type only the password at the prompt.

## Run

With Node 20 or newer:

```
node scripts/load-check.mjs
```

Without Node on the machine, in a container (Docker Desktop reaches the host as `host.docker.internal`):

```
MSYS_NO_PATHCONV=1 docker run --rm -e LOAD_ORGANIZER_EMAIL -e LOAD_ORGANIZER_PASSWORD -v "$(pwd -W)/scripts:/scripts:ro" node:22 node /scripts/load-check.mjs --base http://host.docker.internal:18090
```

Options: `--base` (default `http://localhost:18090`, the web address), `--users 50`, `--pollers 50`, `--seconds 30` (the least time the pollers run; every poller asks about every 3 seconds, as the browser does, ADR-0011).

## What it does

1. Signs in as the organizer, creates the event "Load check event ...", saves a layout with enough seats, publishes it and waits until Booking has the seats.
2. Registers 50 customers (`load-<time>-<n>@example.com`) and signs them in.
3. Starts the pollers. They run during everything below.
4. All customers hold two seats of their own at the same moment, then release them.
5. All customers ask for the same two seats at the same moment. One may win. The winner releases.
6. Waits until the pollers have run long enough, stops them and prints the report.

The exit code is 0 when every line says `ok` and 1 otherwise.

## What it leaves behind

One published event ("Load check event ...", in 2099) and 50 customer accounts. There is no way to delete them from the application yet. On a development machine they do no harm; the event is the last one in the list.

## Reading a failure

- **FAIL "no call was refused by the rate limit":** the limits were not raised (step 2 above). The result of the other lines does not count then.
- **FAIL on holds p95:** run it a second time first, because the first run warms up the JVMs and the database. If it stays slow, look at the Booking logs and at the lock waits in `pg_stat_activity` during the run.
- **FAIL on the contest:** more or fewer than one winner is a bug in the seat locking (ADR-0012), not a slow machine. Keep the output and the Booking logs.
- Results from a laptop with Docker Desktop are a smoke signal, not a capacity figure. The numbers that count are the ones from the VPS (Phase 5).
