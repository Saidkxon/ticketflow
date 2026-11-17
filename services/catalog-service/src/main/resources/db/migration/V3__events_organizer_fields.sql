-- Organizer fields for events (ADR-0005 conventions; V2 is never edited).
-- owner_id is the Identity user ID of the organizer who created the event. It is a plain value, not a
-- foreign key: Catalog never reads Identity's tables (NFR-2).
-- Rows that exist before this migration can only be hand-made test rows (there was no write endpoint),
-- so they get placeholder values instead of failing the migration. The all-zero owner matches no real user.
ALTER TABLE events
    ADD COLUMN owner_id   uuid,
    ADD COLUMN venue_name text,
    ADD COLUMN city       text,
    ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();

UPDATE events
   SET owner_id   = '00000000-0000-0000-0000-000000000000',
       venue_name = 'Unknown venue',
       city       = 'Unknown city'
 WHERE owner_id IS NULL;

ALTER TABLE events
    ALTER COLUMN owner_id   SET NOT NULL,
    ALTER COLUMN venue_name SET NOT NULL,
    ALTER COLUMN city       SET NOT NULL,
    ADD CONSTRAINT events_venue_name_length CHECK (length(venue_name) BETWEEN 1 AND 200),
    ADD CONSTRAINT events_city_length CHECK (length(city) BETWEEN 1 AND 100);

-- Serves "my events": one organizer's events in start-time order, ties broken by id.
CREATE INDEX events_by_owner_idx ON events (owner_id, starts_at, id);
