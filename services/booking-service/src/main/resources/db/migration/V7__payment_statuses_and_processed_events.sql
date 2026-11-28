-- Booking as the coordinator of the payment saga (ADR-0006), first step: what the saga needs to exist in the database.
-- The code that moves a booking into these statuses comes in the next pull requests.

-- The statuses of ADR-0006. PAYMENT_PENDING: the customer started to pay and a payment is in flight. CONFIRMED: the
-- payment succeeded and the seats are sold.
ALTER TABLE bookings DROP CONSTRAINT bookings_status_check;
ALTER TABLE bookings ADD CONSTRAINT bookings_status_check
    CHECK (status IN ('HELD', 'PAYMENT_PENDING', 'CONFIRMED', 'EXPIRED', 'CANCELLED'));

-- How many payments were started for this hold (R3: at most 3 by default, a setting, not a database rule). Payment's
-- idempotency key is "bookingId:attempt", and its attempt number is 1 to 99, so the counter has the same range.
ALTER TABLE bookings
    ADD COLUMN payment_attempts integer NOT NULL DEFAULT 0
        CONSTRAINT bookings_payment_attempts_check CHECK (payment_attempts BETWEEN 0 AND 99);

-- The events this service has handled (R2). A redelivered or twice published event has the same event_id and is
-- recognized here. The row is written in the transaction that handles the event. Old rows are cleaned in Phase 5.
CREATE TABLE processed_events (
    consumer     text        NOT NULL,
    event_id     uuid        NOT NULL,
    event_type   text        NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (consumer, event_id)
);
CREATE INDEX processed_events_processed_at_idx ON processed_events (processed_at);
