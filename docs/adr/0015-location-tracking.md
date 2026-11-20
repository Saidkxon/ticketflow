# ADR-0015: Location tracking, consent and retention

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-11-18 |
| **Deciders** | Saidafzal (instructor), Claude (architect) |

## Context

The instructor wants TicketFlow to (1) show customers the events nearest to them and (2) let administrators see on a map where consenting users are, with the user's name, and analyse the history of those positions. Positions are personal data: they show where a person lives, works and travels. The decision therefore sets rules for consent, storage, access and deletion before any code is written.

The architect advised against full tracking with history, because "nearest events" needs the position only at the moment of the search. The instructor decided to collect it anyway, because the data is wanted for analytics. This ADR records that decision and the safeguards that go with it.

**Technical limit that shapes the design.** A website can read the device position only while its page is open (Geolocation API, with the browser's permission). A browser gives no location access to service workers or background tasks. So tracking covers only the time the app is open. Tracking after the app is closed needs a native Android or iOS app, which is out of scope.

## Decision drivers

1. Users must know what is collected, for how long and who can see it, and must be able to stop it
2. Collect no more than the instructor's analytics need
3. Personal data stays in one place and never leaks into logs, events or other services
4. Fits the existing services and the plan, with no new database or infrastructure (ADR-0005, ADR-0014)

## Options considered

### Option A: Position per search only, nothing stored
- Pros: no personal data on the server; simplest; nothing to protect or delete
- Cons: no map of users and no history for analytics, which the instructor wants

### Option B: Two levels of consent, stored in Identity (chosen)
- Pros: delivers the map and the history; the data sits with the user account, so one service owns it and deletes it; no new infrastructure
- Cons: Identity now holds sensitive data and needs retention, audit and access rules

### Option C: Continuous tracking over a WebSocket into a separate location service
- Pros: live updates
- Cons: a new service and connection type for data that only needs saving; ADR-0011 already decided against pushing; larger attack surface

## Decision

**Option B.** The rules:

**L1. Two levels, chosen on a consent screen.** The screen appears after registration and after login for every user who has not answered. It states what is collected, how long it is kept, who can see it, and that tracking works only while the app is open.
- **Full:** the app saves a position trail (history), kept **30 days**.
- **Minimum:** the same trail, kept only **5 days**.
- Both levels collect the same way; they differ only in how long the history is kept. The latest position of a user is simply the newest point of the trail.

**L2. "Not now".** A small text link under the two buttons skips the question, so no tracking starts and the screen appears again at the next login. Showing the link is controlled by one switch, `VITE_LOCATION_ALLOW_NOT_NOW` (default `true`). Setting it to `false` removes the link. The architect advises keeping it: consent that cannot be refused is not freely given. If it is removed, that is a product decision against this advice and the risk belongs to the product owner.

**L3. Changing the choice.** A switch in the user's settings changes the level or turns tracking off. Turning off deletes the user's whole history. Switching from Full to Minimum deletes the points older than 5 days. The consent text has a version number, stored with the answer, so a changed text asks again.

**L4. Owner and storage.** Identity owns the data. New tables: the user's consent (level, text version, time of decision), the position history (one row per point, both levels), and an access log for administrators. All rows reference the user with `ON DELETE CASCADE`. A position has latitude, longitude, accuracy in metres and the time it was recorded; latitude and longitude are range checked, the time may not be in the future. No other service receives positions. Catalog's "nearest events" search takes the position as a one-off query parameter and never stores it.

**L5. Sending positions.** The browser sends a position with ordinary HTTP only while the app is open and the user has consented: when the device has moved about 200 metres, and at most once a minute. The server ignores a point less than 30 seconds after the previous one. No WebSocket is used (ADR-0011).

**L6. Administrators.** Users with the `ADMIN` role can read the newest point of every consenting user, with their display name, and the history of any consenting user. Every such read is written to the access log (who, when, whose data or "all"). The map view shows the aggregate and the latest positions; the per-user history is a separate explicit call. The consent text names administrators as readers.

**L7. Retention job.** A scheduled job in Identity deletes points older than the user's retention (5 days for Minimum, 30 days for Full) every hour. It is idempotent.

**L8. No leaks.** Coordinates are never written to logs, metrics, traces or events, in addition to the existing rule for names and emails (ADR-0008). Request logging records the route template, never the body.

**L9. The map.** The administrator map uses the Google Maps JavaScript API behind one small web component, so another provider can replace it. The API key is restricted to the site's address, kept in an environment variable and never committed. A budget alert and a daily quota are set in Google Cloud. The free monthly allowance for map loads was 10,000 on 2026-11-18 (Google Maps Platform pricing page); a billing account may be needed and must be checked when the key is created.

**L10. Gateway.** The location and administrator paths are added to the gateway's protected prefixes.

## Consequences

**Positive**
- Delivers the map and the analytics the instructor wants, with no new service or database
- Deleting an account or turning tracking off removes the data in one service
- Reads by administrators are traceable

**Negative / risks**
- Sensitive data is stored; a leak would reveal people's movements. Mitigations: only Identity has it, nothing is logged, retention is short, reads are audited
- Personal-data law may impose more than this ADR does (consent form, notice, deletion on request, cross-border transfer when Google Maps is used). The architect is not a lawyer; this must be checked before real users are served
- Tracking stops when the browser tab is closed, so history has gaps. The consent text says so
- The browser battery cost of watching the position is small but real; the 200 m and one-minute limits keep it low

## Revisit when

- Real users are served (legal review first)
- The instructor wants tracking with the app closed (native app needed)
- Analytics needs outgrow reading from Identity's tables
- Google Maps usage approaches the free allowance

## References

- Charter v1.0: section 8 (security and privacy), NFR on personal data
- ADR-0004 (roles, ADMIN), ADR-0005 (one database per service), ADR-0008 (logging rules), ADR-0011 (no pushing), ADR-0013 (gateway routes)
- Google Maps Platform pricing: https://developers.google.com/maps/billing-and-pricing/pricing (checked 2026-11-18)
- **2026-11-19, L1 as built (Identity).** Migration V3 adds `location_consents` (level FULL, MINIMUM or OFF; OFF is an answer, so the person is not asked again until the text version changes), `location_positions` and `location_access_log`, all with `ON DELETE CASCADE`. The tables are read and written with plain SQL (JdbcClient), because they are rows to append and delete, not aggregates; the rest of Identity uses JPA. Endpoints: `GET` and `PUT /location/consent`, `POST /location/positions` (201 stored, 202 ignored when less than 30 seconds after the previous point; a point may be at most 60 seconds ahead of the server clock), `GET /admin/location/latest` and `GET /admin/location/users/{userId}/history` (Admin only; a read of an unknown user or of someone who said OFF is 404 and is not logged). The retention job runs hourly (first run one minute after start) and also removes points of users who are not tracked. Durations and the consent text version are settings (`ticketflow.identity.location.*`). Only IDs and counts are logged. The access log has no read endpoint yet; it is read in the database until the instructor asks for a screen.
