-- Bookings, what is in them, and the idempotency keys of requests that create them (ADR-0012 part B, ADR-0009 part C).
-- The holds pull request needs only the status HELD. Later pull requests (expiry, release, payment) widen the status
-- check with the statuses they introduce.

-- One booking is one customer's hold of one or more seats of one event. expires_at is the hold deadline, taken from
-- the database clock when the seats are taken; it is the same value as held_until on the booking's seats.
CREATE TABLE bookings (
    booking_id  uuid        PRIMARY KEY,
    event_id    uuid        NOT NULL REFERENCES event_inventory (event_id),
    user_id     uuid        NOT NULL,
    status      text        NOT NULL CHECK (status IN ('HELD')),
    currency    text        NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    total_minor bigint      NOT NULL CHECK (total_minor > 0),
    created_at  timestamptz NOT NULL DEFAULT clock_timestamp(),
    expires_at  timestamptz NOT NULL
);

-- "Does this user already hold seats of this event?" (one active hold per user per event).
CREATE INDEX bookings_event_user_idx ON bookings (event_id, user_id);

-- The seats of a booking and the price each had. event_seats.booking_id says who holds a seat right now; this table
-- remembers what the booking contained, even after its hold expired and the seat went to somebody else.
CREATE TABLE booking_seats (
    booking_id  uuid   NOT NULL REFERENCES bookings (booking_id) ON DELETE CASCADE,
    event_id    uuid   NOT NULL,
    seat_id     uuid   NOT NULL,
    price_minor bigint NOT NULL CHECK (price_minor > 0),
    PRIMARY KEY (booking_id, seat_id),
    FOREIGN KEY (event_id, seat_id) REFERENCES event_seats (event_id, seat_id)
);

-- Idempotency keys (ADR-0009, part C): the key is unique per user. The row is written in the same transaction as the
-- booking, so a key either has its booking or does not exist. A request that fails leaves no key behind, and a retry
-- with the same key simply runs again. booking_id is filled by the same transaction. A cleanup of keys older than
-- 24 hours comes with the expiry job.
CREATE TABLE idempotency_keys (
    user_id         uuid        NOT NULL,
    idempotency_key uuid        NOT NULL,
    request_hash    text        NOT NULL,
    booking_id      uuid        REFERENCES bookings (booking_id) ON DELETE CASCADE,
    created_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, idempotency_key)
);
CREATE INDEX idempotency_keys_created_idx ON idempotency_keys (created_at);
