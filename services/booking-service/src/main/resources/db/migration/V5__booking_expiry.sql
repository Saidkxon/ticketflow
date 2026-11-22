-- Expiry of holds (ADR-0012, part B; ADR-0006). A booking whose hold deadline has passed becomes EXPIRED, and its
-- seats go back to AVAILABLE. Until the sweeper has done that, lazy expiry already treats those seats as free, so
-- correctness never waits for it. The other statuses of ADR-0006 come with the payment slice.
ALTER TABLE bookings DROP CONSTRAINT bookings_status_check;
ALTER TABLE bookings ADD CONSTRAINT bookings_status_check CHECK (status IN ('HELD', 'EXPIRED'));

-- The sweeper looks for HELD bookings whose deadline has passed, oldest first.
CREATE INDEX bookings_expiry_idx ON bookings (expires_at) WHERE status = 'HELD';

-- The sweeper releases "the seats this booking holds"; later steps (confirmation, cancellation) ask the same.
CREATE INDEX event_seats_booking_idx ON event_seats (booking_id) WHERE booking_id IS NOT NULL;
