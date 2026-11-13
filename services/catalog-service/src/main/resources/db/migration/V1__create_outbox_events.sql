-- Transactional outbox (ADR-0003, ADR-0005): an event is written in the same transaction
-- as the business change and published to the broker later by a relay.
CREATE TABLE outbox_events (
    id             uuid        PRIMARY KEY,
    aggregate_type text        NOT NULL,
    aggregate_id   uuid        NOT NULL,
    event_type     text        NOT NULL,
    payload        jsonb       NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    published_at   timestamptz
);

-- The relay reads unpublished rows in order (FOR UPDATE SKIP LOCKED).
CREATE INDEX outbox_events_unpublished_idx ON outbox_events (created_at) WHERE published_at IS NULL;
