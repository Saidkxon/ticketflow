-- Where the venue is, for the "near me" search (FR-4, ADR-0015). Both values are set or both are empty.
-- These are the venue's coordinates, not a user's: a user's position is never stored in Catalog.
-- Events without coordinates simply never match a "near" search.
ALTER TABLE events
    ADD COLUMN venue_latitude  double precision,
    ADD COLUMN venue_longitude double precision,
    ADD CONSTRAINT events_venue_coordinates_pair
        CHECK ((venue_latitude IS NULL) = (venue_longitude IS NULL)),
    ADD CONSTRAINT events_venue_latitude_range
        CHECK (venue_latitude IS NULL OR venue_latitude BETWEEN -90 AND 90),
    ADD CONSTRAINT events_venue_longitude_range
        CHECK (venue_longitude IS NULL OR venue_longitude BETWEEN -180 AND 180);
