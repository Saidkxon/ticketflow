-- Seat inventory of an event (ADR-0012, part A). Booking builds it from the immutable layout that Catalog published:
-- one row per seat, with the price copied in, so selling never needs Catalog (NFR-3).
--
-- An event is on sale when it has an event_inventory row. The row is written in the same transaction as all of the
-- event's seats, so it appears only when provisioning is complete: until then the event is "not on sale yet".
CREATE TABLE event_inventory (
    event_id       uuid        PRIMARY KEY,
    layout_version bigint      NOT NULL,
    currency       text        NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    total_seats    int         NOT NULL CHECK (total_seats > 0),
    provisioned_at timestamptz NOT NULL DEFAULT now()
);

-- The seat ID is the opaque ID issued by Catalog. Section, row and number are display labels copied for the
-- booking screens. Money is an integer in the smallest currency unit (ADR-0005).
-- The hold columns (owner and deadline) are added with the pull request that takes holds.
CREATE TABLE event_seats (
    event_id     uuid   NOT NULL REFERENCES event_inventory (event_id) ON DELETE CASCADE,
    seat_id      uuid   NOT NULL,
    section_name text   NOT NULL,
    row_label    text   NOT NULL,
    seat_number  int    NOT NULL CHECK (seat_number >= 1),
    tier_name    text   NOT NULL,
    price_minor  bigint NOT NULL CHECK (price_minor > 0),
    state        text   NOT NULL DEFAULT 'AVAILABLE' CHECK (state IN ('AVAILABLE', 'HELD', 'SOLD')),
    PRIMARY KEY (event_id, seat_id)
);
