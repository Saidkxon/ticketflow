-- Who holds or owns a seat, and until when a hold lasts (ADR-0012, part B). Nothing writes these columns yet: holds
-- come with the next pull requests, but the availability read already has to apply the rule "a hold whose deadline
-- has passed is not a hold".
--
-- All deadlines and comparisons use the database clock (ADR-0012, "one clock").
ALTER TABLE event_seats
    ADD COLUMN booking_id uuid,
    ADD COLUMN held_until timestamptz,
    ADD CONSTRAINT event_seats_state_columns CHECK (
        (state = 'AVAILABLE' AND booking_id IS NULL AND held_until IS NULL)
        OR (state = 'HELD' AND booking_id IS NOT NULL AND held_until IS NOT NULL)
        OR (state = 'SOLD' AND booking_id IS NOT NULL AND held_until IS NULL));
