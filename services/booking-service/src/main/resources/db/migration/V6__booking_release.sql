-- Releasing a hold and listing a customer's bookings (ADR-0006: HELD to CANCELLED when the customer abandons).
-- A released hold becomes CANCELLED and its seats go back to AVAILABLE in the same transaction.
ALTER TABLE bookings DROP CONSTRAINT bookings_status_check;
ALTER TABLE bookings ADD CONSTRAINT bookings_status_check CHECK (status IN ('HELD', 'EXPIRED', 'CANCELLED'));

-- "My bookings", newest first (the list is read by one customer at a time).
CREATE INDEX bookings_user_idx ON bookings (user_id, created_at DESC, booking_id DESC);
