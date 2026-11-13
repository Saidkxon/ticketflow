-- Events (ADR-0005 conventions: UUID ids from the application, timestamptz, created_at, version).
-- published_at IS NULL means the event is still a draft and is not listed publicly.
CREATE TABLE events (
    id           uuid        PRIMARY KEY,
    title        text        NOT NULL CHECK (length(title) BETWEEN 1 AND 200),
    starts_at    timestamptz NOT NULL,
    published_at timestamptz,
    created_at   timestamptz NOT NULL DEFAULT now(),
    version      bigint      NOT NULL DEFAULT 0
);

-- Serves the public list: published events in start-time order, ties broken by id.
CREATE INDEX events_published_by_start_idx ON events (starts_at, id) WHERE published_at IS NOT NULL;
