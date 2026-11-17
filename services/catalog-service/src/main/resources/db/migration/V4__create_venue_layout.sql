-- Venue layout of an event (FR-3, ADR-0012). All rows belong to one event and go with it when the event is
-- deleted. Layout is edited only while the event is a draft; publishing freezes it.
--
-- Money is an integer in the smallest currency unit plus a currency code (ADR-0005). The currency belongs to the
-- event, because all tiers of one event are sold in the same currency.
ALTER TABLE events
    ADD COLUMN currency text,
    ADD CONSTRAINT events_currency_format CHECK (currency IS NULL OR currency ~ '^[A-Z]{3}$');

CREATE TABLE price_tiers (
    id          uuid   PRIMARY KEY,
    event_id    uuid   NOT NULL REFERENCES events (id) ON DELETE CASCADE,
    name        text   NOT NULL CHECK (length(name) BETWEEN 1 AND 50),
    price_minor bigint NOT NULL CHECK (price_minor > 0),
    position    int    NOT NULL,
    UNIQUE (event_id, name)
);

-- A section is a block of lettered rows (A, B, C, ...) with the same number of seats in each row, and one tier.
CREATE TABLE sections (
    id            uuid PRIMARY KEY,
    event_id      uuid NOT NULL REFERENCES events (id) ON DELETE CASCADE,
    name          text NOT NULL CHECK (length(name) BETWEEN 1 AND 50),
    position      int  NOT NULL,
    row_count     int  NOT NULL CHECK (row_count BETWEEN 1 AND 26),
    seats_per_row int  NOT NULL CHECK (seats_per_row BETWEEN 1 AND 100),
    tier_id       uuid NOT NULL REFERENCES price_tiers (id),
    UNIQUE (event_id, name)
);

-- One row per physical seat. The id is the opaque, immutable seat ID that Booking will use (ADR-0012, rule 5);
-- row and number are display labels only.
CREATE TABLE seats (
    id          uuid PRIMARY KEY,
    event_id    uuid NOT NULL REFERENCES events (id) ON DELETE CASCADE,
    section_id  uuid NOT NULL REFERENCES sections (id) ON DELETE CASCADE,
    row_label   text NOT NULL,
    seat_number int  NOT NULL,
    UNIQUE (section_id, row_label, seat_number)
);

CREATE INDEX seats_by_event_idx ON seats (event_id);
CREATE INDEX sections_by_event_idx ON sections (event_id, position);
CREATE INDEX price_tiers_by_event_idx ON price_tiers (event_id, position);
