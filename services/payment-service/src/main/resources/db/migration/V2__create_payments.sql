-- Payment attempts (ADR-0006, ADR-0009 part C). One row is one attempt to charge for one booking. Booking asks for an
-- attempt with the idempotency key "bookingId:attemptNumber", so the pair is unique: the same request repeated (a
-- retry after a timeout, a second click) finds the row that already exists and never creates a second payment (FR-11).
-- Payment knows the booking only as an ID; it knows no seats and no customer.
--
-- Status: PENDING is the start; SUCCEEDED and FAILED are set when the provider has answered (a later pull request).
-- A refund state is added by the refunds pull request.
CREATE TABLE payments (
    payment_id     uuid        PRIMARY KEY,
    booking_id     uuid        NOT NULL,
    attempt_number integer     NOT NULL CHECK (attempt_number BETWEEN 1 AND 99),
    amount_minor   bigint      NOT NULL CHECK (amount_minor > 0),
    currency       text        NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    status         text        NOT NULL CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    created_at     timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at     timestamptz NOT NULL DEFAULT clock_timestamp(),
    -- The idempotency key. The database, not the code, is what makes a second charge impossible.
    CONSTRAINT payments_booking_attempt_key UNIQUE (booking_id, attempt_number)
);
