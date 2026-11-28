# Phase 4c plan: Payment, confirmation and tickets

| | |
|---|---|
| **Status** | Draft, 2026-11-24. Waiting for the instructor's approval and for answers to the questions below |
| **Goal** | A customer can pay for held seats with a simulated provider and get a confirmed booking, see their tickets, cancel and get a refund, and is notified by email. Every failure path ends in a clear final state with money returned when it should be (ADR-0006) |
| **Out of scope** | A real payment provider, partial refunds, several payment methods, QR codes on tickets, an organizer sales dashboard (FR-17 is planned after the UX pass), real outgoing email (Phase 5) |
| **Estimate** | 16 pull requests (numbers 16 to 30 and 17b continue the numbering of Phase 4b). About 3 weeks at 10 to 15 hours a week. This is the riskiest phase so far: money, concurrency and three services in one flow |

## What this phase builds on

- **ADR-0006** decides the whole flow: Booking owns the state machine, Payment is a participant, Notification only reacts to events. The rules R1 to R8 and the nine test obligations are the specification of this phase. Nothing here changes them.
- **ADR-0002, ADR-0003, ADR-0009:** one quick idempotent call from Booking to Payment, the result as an event through the outbox, schemas in `contracts/`, consumers that tolerate duplicates.
- **Phase 4b** gave us holds, expiry and release, and a web app that holds seats. The hold panel already says "Payment is not available yet"; PR 28 replaces that sentence with a button.

## Part 0: Test data for the stack (1 pull request)

| # | Pull request | Delivers |
|---|---|---|
| 16 | Test organizer and the full hold path in the smoke test (in review) | The smoke script creates an organizer without a human: it registers a user and gives that user the `ORGANIZER` role directly in the Identity database of the test stack (the first-admin bootstrap needs an Identity restart and is for operators, ADR-0004). With that organizer the script creates an event with a small layout, publishes it, waits until Booking has the seats, holds seats, releases them, and checks that a second user cannot take held seats. Also the load check promised in Phase 4b: a script that runs 50 simultaneous users against holds and 50 pollers against availability and prints p95 times (targets: holds under 500 ms, availability under 200 ms). The smoke part runs in CI; the load check runs on your machine and has a runbook, `docs/runbooks/load-check.md` |

## Part 1: Payment service (5 pull requests)

| # | Pull request | Delivers |
|---|---|---|
| 17 | Payment skeleton (merged, #73) | New service: Flyway, health, CI, Compose service and database, outbox relay copy (ADR-0001 rule R2). Reachable only inside the Docker network; the gateway does not route to it (ADR-0002). Everything under `/internal/v1` needs a **service token** (ADR-0004, Part D): Payment checks it with its own verifier (RS256, issuer Identity, audience `payment-service`, caller in `sub`, permissions in `scope`, a token with `roles` is refused). The check is tested with locally made keys and tokens, because Identity cannot issue service tokens yet |
| 17b | Identity issues service tokens (merged, #74) | ADR-0004, Part D was never built, because no internal call existed. Identity gets `POST /internal/v1/service-tokens`, not routed by the gateway (`contracts/identity.internal.openapi.yaml`): a registered service (`booking-service`, with its secret from the environment) asks for a token for one target service and some scopes, and gets a token that lives 5 minutes. The registered services and what each may ask for are configuration (`ticketflow.identity.service-clients`), not database rows. A wrong secret and an unknown service give the same 401, a target or scope that is not allowed is a 403 for the whole request, and a token has no `roles`, so no user endpoint accepts it. The smoke test gets a real token and shows Payment accepting it. No caller yet: Booking starts calling in PR 18 and 22 |
| 18 | Payment attempts (merged, #75) | `POST /internal/v1/payment-attempts` (scope `payment.attempt.create`, `contracts/payment.internal.openapi.yaml`) with the idempotency key `bookingId:attemptNumber` (ADR-0009): the first request creates a payment in the state `PENDING` and answers 202, the same request repeated answers 202 with the same payment and creates nothing, the same key with another amount or currency is a 422. The unique key in the database, not a check in the code, makes a second payment impossible (FR-11, test obligation 7, also tested with twelve simultaneous requests). `GET /internal/v1/payment-attempts/{paymentId}` (scope `payment.attempt.read`) reads one. Amount and currency come from the call. States `PENDING`, `SUCCEEDED`, `FAILED`; only `PENDING` is used until PR 19. No caller yet: Booking calls it in PR 22 |
| 19 | Simulated provider (merged, #76) | The provider the MVP uses, behind an interface (`PaymentProvider`: `charge` and `lookup`, both idempotent by the key `bookingId:attempt`) so a real one can replace it. The outcome is chosen by the test card number the customer types (`testCard` in the create call, never stored or logged, only the scenario and the last four digits are kept): `4242424242424242` succeeds, `4000000000000002` is declined, `4000000000000259` times out and shows its success minutes later, `4000000000000077` reports its success twice; any other number is declined and no card is declined too. The simulator keeps its own charge table, so it behaves like a real provider. A processor in Payment (every second) charges a pending payment once and afterwards only asks the provider what became of it. A timeout and "still processing" leave the payment `PENDING`; it becomes `FAILED` only when the provider declines or confirms by a lookup that it holds no charge (R5). The answer is applied by one step that changes only a payment that is still `PENDING`, so a result that is reported twice, or a late one that contradicts a decided payment, changes nothing and writes no second event (R4). `PaymentSucceeded` and `PaymentFailed` go through the outbox in the same transaction as the status change; schemas in `contracts/events/`. Booking consumes them in PR 21 |
| 20 | Refunds in Payment (merged, #77) | Payment consumes `RefundRequested` from its own queue `payment.booking.refund-requested` (with retry and dead-letter queues, as Booking's first consumer; schema in `contracts/events/`). The request names a payment, a booking and a reason (`booking_expired`, `seats_unavailable`, `booking_cancelled`), no amount: full refund. A refund is a step in the life of the payment: `SUCCEEDED` becomes `REFUND_PENDING` and then `REFUNDED`, each by a conditional update, so a request that is redelivered or arrives at the same moment twice starts one refund and a second answer changes nothing (test obligation 8). The processor sends the refund through the provider interface (`refund`, `lookupRefund`, idempotent by the key of the charge), treats a timeout like R5 (the payment stays `REFUND_PENDING` until the provider confirms), and writes `RefundSucceeded` through the outbox in the same transaction. A request that can never work (unknown payment, another booking's payment, a payment that failed, an unknown reason) is parked in the dead-letter queue at once. No producer yet: Booking sends `RefundRequested` in PR 24 and 25, so the smoke test cannot reach this path before then; the tests use the real broker |

## Part 2: Booking becomes the saga coordinator (5 pull requests)

| # | Pull request | Delivers |
|---|---|---|
| 21 | Consuming events (in review) | Booking's consumer of Payment's events: `PaymentSucceeded`, `PaymentFailed` and `RefundSucceeded`, each from its own queue (`booking.payment.<event>`, with retry and dead-letter queues, same behaviour as the first consumer; schemas already in `contracts/events/`). A table `processed_events` (consumer, event ID) so that a redelivered event does nothing (R2): the ID is recorded and the handler runs in one transaction, so a failing handler leaves no record and the event is tried again. New statuses `PAYMENT_PENDING` and `CONFIRMED` in the database check and the counter `payment_attempts` on `bookings` (0 to 99; the limit of 3 stays a setting). The handler is a seam that only logs for now: confirmation and failure come in PR 23, late payments in PR 24, the end of a refund in PR 25. A parked payment message gets a runbook entry in `deploy/README.md` |
| 22 | Start payment | `POST /bookings/{id}/pay`. One transaction moves the booking to `PAYMENT_PENDING`, extends the hold by the grace period and records the request; then Booking calls Payment. Lazy expiry and the sweeper must leave seats alone during the grace period (R6). Payment down: the hold simply runs out (test obligation 5) |
| 23 | Confirmation | The confirmation transaction: lock the seats in order, check that every seat is still held by this booking, set them `SOLD`, the booking `CONFIRMED`, and write `BookingConfirmed` (R1, R2). `PaymentFailed` returns the booking to `HELD` while attempts remain and the hold is valid; the last failure expires it (R3). Tests: duplicate events, confirmation against expiry at the same moment, a retried payment (obligations 1, 3, 4) |
| 24 | Late and unusable payments | `PaymentSucceeded` after the booking expired or was cancelled, or after the seats went to somebody else: nothing is sold, `RefundRequested` is written (R4). The unknown outcome of a timeout followed by a late success (R5). Tests: obligations 2 and 6 |
| 25 | Cancel after confirmation | `POST /bookings/{id}/cancel` for a confirmed booking: `CANCELLED`, seats available again, `BookingCancelled` (R7, FR-9). The refund follows through Payment. Tests: obligation 8; the service killed between receiving an event and committing (obligation 9, with a restart test) |

## Part 3: Notification service (2 pull requests)

| # | Pull request | Delivers |
|---|---|---|
| 26 | Notification skeleton and delivery log | New service: Flyway, health, CI, Compose, its own database, a consumer of the booking and payment events, a delivery log so one event never sends two emails. It exposes no endpoint to other services (ADR-0002). A mail catcher container (MailHog) in Compose so development sends no real email |
| 27 | Templates | Emails for a confirmed booking, a payment that failed, a hold that expired, a booking cancelled and a refund made, plus the "payment received after expiry, refunded" mail that ADR-0006 asks for (FR-12). Plain text first, in English |

## Part 4: Web (3 pull requests)

| # | Pull request | Delivers |
|---|---|---|
| 28 | Checkout | A "Pay" button in the hold panel, a card form that explains which test cards give which result, the "processing payment" state with polling (ADR-0011), and a clear page for each outcome: confirmed, declined with a chance to retry, expired, refunded. The countdown keeps running through the grace period |
| 29 | My tickets and cancel | A booking history with status, seats and total (FR-16), a page for one booking, a cancel button with a confirmation step. A booking shows `EXPIRED` for a hold past its deadline, as the API already does |
| 30 | Wrap-up | The smoke test runs the whole path: browse, hold, pay, confirmed, cancel, refund. The load check from PR 16 runs again with payments. ADR clarifications as built, exit checks by hand, plan close-out |

## Questions for the instructor (the default applies if you do not answer)

1. **Refund on cancel (ADR-0006, open question 1):** full refund at any time before the event. Default: yes, for the MVP.
2. **Payment attempts per hold:** up to 3. Default: 3.
3. **Grace period while a payment is in flight:** 2 minutes, once per attempt. Default: 2 minutes.
4. **Notification channel:** email, with a mail catcher in development and no real sending until Phase 5. Default: yes. (The architecture document marks this as "to confirm at the first Notification slice".)
5. **Test cards:** the result of a payment is chosen by the card number typed in the checkout form. Default: yes, because it is easy to demonstrate and to test.
6. **Tickets:** a booking reference is the ticket in the MVP; no QR code and no PDF. Default: yes.

## Risks and how this plan handles them

- **Java and Spring code is not compiled in my sandbox** (Maven Central is unreachable there). Every Java pull request is verified by you with `./mvnw verify` for the services it touches, then Compose and the smoke test, as in Phase 4b. I check syntax and run the SQL checks against PostgreSQL, but a green build on your machine is the proof. The web app is fully checked in my sandbox.
- **Concurrency and money:** the nine test obligations of ADR-0006 are written as tests in the pull requests above, against a real PostgreSQL and, where an event goes through the broker, a real RabbitMQ (Testcontainers). A pull request that adds a saga rule is not approved without its test.
- **Three services in one flow:** the order of the parts is chosen so that each pull request is useful and testable alone. Payment can be tried with `curl` before Booking calls it, and Booking can be tried with a stub payment result before the real one exists.
- **Time:** about 3 weeks is an estimate for the pull requests as listed. If the saga tests find a design problem, the design changes in ADR-0006 first, in its own pull request.

## After this phase

The UX design pass (about 1 to 2 weeks), then Phase 5 (production Compose, VPS and HTTPS, backups, security review, audits and the rest of the list in `docs/plans/phase-4b-booking.md`).
