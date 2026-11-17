# ADR-0004: Authentication and authorization

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-11-08 |
| **Deciders** | Saidafzal (instructor), Claude (architect) |

## Context

Customers, organizers and admins use the web app through the API gateway. Identity owns users, credentials and roles (charter section 8). Several decisions already point here: ADR-0010 postponed browser token handling to this ADR; ADR-0002 says internal endpoints are not exposed through the gateway and that service-to-service authentication is decided here; the charter requires roles (FR-1, FR-2) and no secrets in the repository (NFR-7).

This ADR decides four things: **(A)** who issues identities, **(B)** where tokens are validated, **(C)** how the browser stores them, and **(D)** how services authenticate each other. It also fixes the authorization model.

Security matters here more than almost anywhere else in the project: this is where customer accounts and payments are protected.

## Decision drivers

1. Safe by default: no single mistake (for example a gateway bypass) should expose data
2. Stay within the laptop's memory budget (about 3.8 GB free in practice) and a solo developer's time
3. Learn the patterns real teams use: JWT, resource servers, refresh-token rotation
4. Keep the browser safe against token theft through cross-site scripting (XSS)
5. Do not build more than the MVP needs (no social login, no multi-factor)

## Part A: who issues identities

### Option A1: Keycloak (or a similar ready-made identity provider)
- Pros: production-grade features out of the box (OIDC, MFA, social login, admin console)
- Cons: heavy to run locally (a Java server plus its own database, hundreds of MB of memory); hides the mechanics we want to learn; the charter already assigns users and credentials to our own Identity service

### Option A2: Our own Identity service using Spring Security and signed JWTs
- Pros: matches the charter; light; we learn the mechanics; enough for registration, login and roles
- Cons: we own the security-sensitive code, so reviews must be strict; features like MFA or social login would have to be added later

### Option A3: Full OAuth2/OIDC authorization server built by us
- Pros: standards-complete
- Cons: far more than the MVP needs; large learning and testing cost

**Decision A: Option A2.** Identity issues short-lived signed access tokens and rotating refresh tokens. If MFA, social login or single sign-on become requirements, we revisit A1.

## Part B: where tokens are validated

### Option B1: Only the gateway validates; services trust it
- Pros: simple
- Cons: a gateway bypass, a misconfigured network or a compromised container grants full access to the services behind it

### Option B2: The gateway validates, and every service validates as well
- Pros: defense in depth; each service is safe on its own; the gateway rejects bad requests early and cheaply
- Cons: every service needs the security setup (small, standard Spring configuration)

**Decision B: Option B2.** The gateway rejects missing, expired or malformed tokens early. **Every service also acts as a resource server** and validates the signature, expiry, issuer and audience itself, using Identity's public keys. Authorization is decided inside each service, never at the gateway alone.

## Part C: browser token storage

### Option C1: Both tokens in localStorage
- Pros: simplest to code
- Cons: any XSS bug lets an attacker read both tokens

### Option C2: Access token in memory; refresh token in an HttpOnly cookie
- Pros: JavaScript cannot read the refresh token, so XSS cannot steal the long-lived credential; the short-lived access token lives only in memory and is lost on page reload, then restored through the refresh cookie
- Cons: needs cookie settings and cross-site request protection on the refresh endpoint; a bit more work

**Decision C: Option C2.**

## Decision

### Tokens

- **Access token:** a signed JWT, **default lifetime 15 minutes**, sent as `Authorization: Bearer ...`. Signed with an **asymmetric key** (algorithm fixed at implementation). The private key exists only in Identity. Other components fetch public keys from Identity's JWKS endpoint; keys carry an ID so they can be rotated.
- **Claims:** `sub` (opaque user ID), `roles`, `iss`, `aud`, `iat`, `exp`, `jti`. No email, name or other personal data in the token.
- **Refresh token:** an opaque random value, **default lifetime 7 days**, **rotated on every use**. Only a hash is stored in Identity's database. **Reuse of an already-used refresh token revokes that whole token family** (a sign of theft).
- **Refresh cookie:** `HttpOnly`, `Secure`, `SameSite=Strict`, restricted to the refresh path. The refresh endpoint additionally requires a custom request header and checks the request origin, as defense against cross-site request forgery.
- **Revocation:** access tokens are not individually revoked in the MVP. They expire within 15 minutes, which is also how long a role change can take to apply. Logout revokes the refresh token family.

### Passwords and accounts

- Passwords are hashed with Spring Security's delegating password encoder (a strong, adaptive algorithm that can be upgraded without a flag day). Passwords are never logged or returned.
- Login attempts are rate limited at the gateway and slowed down per account after repeated failures. Error messages do not reveal whether an email exists.
- Registration creates a **Customer**. **Organizer** and **Admin** roles are granted by an Admin; the first Admin is created by a documented bootstrap procedure, never by public registration.

### Authorization model

- Roles: `CUSTOMER`, `ORGANIZER`, `ADMIN`.
- **Role checks and ownership checks both happen in the service that owns the data.** For example, an organizer may edit only their own events, and a customer may read only their own bookings. The user ID comes from the validated token (`sub`), never from a request field.
- Browsing events is public; holding seats, paying, booking history and all management features require authentication.

### Service-to-service (Part D)

- Internal endpoints live under a separate path prefix, are **not routed by the gateway**, and require a **service token**: a short-lived JWT issued by Identity to a registered service (client-credentials style), with the caller as subject, the target service as audience and a narrow scope (for example, `catalog.snapshot.read`).
- Service credentials come from the environment or a secret store, never from the repository (NFR-7).
- This is introduced with the **first internal call** (Booking to Catalog, expected in Phase 4b), not in the walking skeleton.
- A user token is never accepted on an internal endpoint, and a service token is never accepted on a public endpoint.
- RabbitMQ uses a separate credential per service with permissions limited to the queues and exchanges it needs (a new rule here that extends ADR-0003).

### Transport and other controls

- HTTPS everywhere outside local development; TLS ends at the gateway in production, with the exact setup decided at the deployment gate.
- Cross-origin requests (CORS) are allowed only from the web app's own origin.
- Security events (failed logins, refresh-token reuse, authorization failures) are logged with the correlation ID, without secrets (see ADR-0008).
- Dependency and secret scanning run in CI (NFR-7).
- Library versions and the signing algorithm are verified against current official documentation and pinned when Identity is scaffolded.

## Consequences

**Positive**
- A gateway bypass does not expose data, because each service checks tokens itself
- The long-lived credential is hidden from JavaScript
- Identity stays small and fits on a laptop
- A realistic experience with patterns used in production systems

**Negative / risks**
- **We own security-sensitive code.** Mitigation: use Spring Security's standard components, avoid custom cryptography, and make security review a mandatory checklist item for every Identity and gateway PR.
- **Role changes and compromised access tokens stay valid for up to 15 minutes.** Accepted for the MVP; shorter lifetime or a revocation list are later options.
- **Cookie-based refresh needs the web app and API to be served in a compatible way** (same site, or carefully configured). Mitigation: decided together with the deployment target and tested in the walking skeleton.
- **Key management:** a leaked private key breaks everything. Mitigation: the private key never leaves Identity; rotation is supported by key IDs and rehearsed in a test.
- **More setup in every service.** Mitigation: a documented standard configuration reused by copy (not shared code, ADR-0001 rule R2).

## Test obligations

1. Expired token, wrong audience, wrong issuer and tampered signature are each rejected by the gateway **and** by a service called directly
2. A request that bypasses the gateway without a token gets 401
3. Role escalation: a Customer calling an organizer endpoint gets 403
4. Ownership: a customer reading or cancelling another customer's booking is refused; an organizer editing another organizer's event is refused
5. Refresh rotation: using an old refresh token revokes the whole family
6. Brute force: repeated failed logins are rate limited
7. An internal endpoint rejects a user token; a public endpoint rejects a service token
8. Key rotation: tokens signed with the previous key still validate until they expire, and new tokens use the new key
9. A repository scan finds no secrets (NFR-7)

## Open questions for the instructor (defaults apply if unchanged)

1. **Token lifetimes:** 15 minutes for access, 7 days for refresh (default)?
2. **Becoming an Organizer:** granted by an Admin (default), or self-service sign-up?
3. **Email verification and password reset:** out of scope for the MVP (default), or required from the start? They depend on the Notification service.

## Revisit when

- MFA, social login or single sign-on are required (consider Keycloak or a full authorization server)
- A real payment provider requires stronger identity guarantees
- The 15-minute window for role changes becomes unacceptable

## Related decisions

- **ADR-0002:** internal endpoints and calls
- **ADR-0003:** broker credentials
- **ADR-0008:** security logging and alerts
- **ADR-0009:** error format for 401 and 403 responses
- **ADR-0010:** browser token handling is settled here

## References

- Charter v1.0: FR-1, FR-2, NFR-6, NFR-7, section 8
- ADR-0002 (rule 9: internal endpoints are not exposed through the gateway), ADR-0010 (Consequences: token handling deferred to this ADR), ADR-0003 (event transport)

## Clarifications

- **2026-11-14, public key endpoint.** Identity serves its public keys at `/.well-known/jwks.json` on its own application port. The endpoint needs no token, because the keys are public and must be readable before any token can be validated; this is the one exception to "internal endpoints need a service token". The gateway does not route it, so it is reachable only inside the container network. The contract is `contracts/identity.openapi.yaml`.
- **2026-11-14, registration and email.** Registering an email that already exists returns 409 `EMAIL_ALREADY_REGISTERED`. The MVP has no email verification, so a neutral answer is not possible without confusing real users. This lets a caller test whether an email is registered; it is limited by the gateway rate limit on `/auth/**`. Login errors stay neutral, as written above.
- **2026-11-14, refresh tokens in detail.** One login starts one family of refresh tokens; each refresh marks the used token and creates the next one in the same family, in one database transaction that locks the token row. If two requests present the same token at the same moment, exactly one succeeds and the other counts as reuse, so the family is revoked and the user must log in again. This is the strict reading of "reuse revokes the family", chosen because a grace window would also let a thief refresh. The consequence for the web app: refresh from one place only (one shared refresh call, not one per tab or per component). The refresh and logout endpoints are guarded by the header `X-TicketFlow-Client: web` and by an `Origin` equal to a configured web origin (`IDENTITY_ALLOWED_ORIGINS`); a request without an `Origin` is refused, because browsers send one on every POST. A rejected refresh clears the cookie. Expired token rows are not deleted yet; a cleanup job is on the Phase 5 list.
- **2026-11-15, how tokens are checked.** Every service checks tokens with its own copy of one small class (`AccessTokenVerifier`, built directly on the Nimbus library that Spring Security uses), not with a framework resource-server setup. It accepts only RS256 (an unsigned token, or one signed with a shared secret, is refused), requires `sub`, `exp`, `iat`, `jti`, `roles`, the exact issuer and the audience, and allows 5 seconds of clock difference. A role name the service does not know is ignored. Any failure gives the same plain 401 `UNAUTHENTICATED`. Identity reads the public keys from its own key files; other services will read `/.well-known/jwks.json` and cache it, refetching when an unknown key ID appears. Services take the user ID from `sub` and read roles and names from their own data where it matters, so a token cannot grant more than the database does. A valid token for a user who was deleted gets 401. Catalog gets its checks together with its first protected endpoint (organizer event management); until then all its endpoints are public reads.
- **2026-11-15, roles and the first Admin.** `GET /users` and `PUT /users/{id}/roles` are Admin-only; the rule covers every path under `/users` except `/me`, so a later path is closed by default. The check reads the roles in the token, so a demoted Admin keeps Admin rights for up to 15 minutes, the same delay as every role change. A role change is refused when an Admin removes their own `ADMIN` role, and `CUSTOMER` is always kept. The first Admin is made by the operator: register normally, set `IDENTITY_BOOTSTRAP_ADMIN_EMAIL` to that email, restart Identity (steps in `deploy/README.md`). The setting only promotes a user who already exists, never creates one. Because email addresses are not verified in the MVP, the operator registers first and checks that registration did not answer 409; this is acceptable for a single-operator system and is revisited if email verification is added. Two role changes to the same user at the same moment give 409 `CONFLICT` to the second.
- **2026-11-15, the web app's part.** The web app keeps the access token only in memory and finds its session again after a reload with one call to `POST /api/v1/auth/refresh`, which the HttpOnly cookie makes possible. All refreshing goes through one shared in-flight call, because a refresh token used twice ends the session (see "refresh tokens in detail" above); this also covers React starting an effect twice in development. It refreshes a minute before expiry, retries a request once after a 401, and any failed refresh signs the user out instead of retrying, since a refresh that may already have been used cannot safely be repeated. Pages that need a signed-in user send visitors to the login page and back. The Secure cookie works on `http://localhost` in Chrome, Edge and Firefox but not in Safari or on a plain-http address, so the app is used over https or localhost.
- **2026-11-17, Catalog checks tokens and the ORGANIZER role.** Catalog now has its own copy of `AccessTokenVerifier`, as Identity and the gateway have (it is a copy of the gateway's, and fetches Identity's keys the same way). Everything under `/api/v1/events` that is not a plain read, and `GET /events/mine`, needs a valid token with the `ORGANIZER` role; a customer gets 403 `FORBIDDEN`, a missing or bad token gets the plain 401. The roles come from the token, so a user whose Organizer role was just removed can still change their drafts for up to 15 minutes, until the access token expires; this is accepted for events (unlike Identity's own `/users/me`, which reads the roles from the database). Ownership is checked in Catalog: the owner is always the user in the token, never a field of the body, and an event of another organizer answers 404 `NOT_FOUND`, exactly like a missing one, so a draft's existence is not revealed. An Admin who also wants to organize needs the `ORGANIZER` role added to their own account.
