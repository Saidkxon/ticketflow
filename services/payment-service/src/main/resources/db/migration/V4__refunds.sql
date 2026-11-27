-- Refunds (ADR-0006, rules R4 and R7). A refund is a step in the life of a payment, not a second record: a payment that
-- SUCCEEDED can become REFUND_PENDING (Booking asked for the money back, the provider has not confirmed) and then
-- REFUNDED. One payment has at most one refund because the status can only move forward from SUCCEEDED, and the
-- processor changes it with a conditional update ("only if it is still REFUND_PENDING"), so a request or an answer that
-- arrives twice changes nothing.
--
-- refund_reason: why Booking asked (the codes of contracts/events/refund-requested.v1.schema.json).
-- refund_reference: the provider's reference of the refund, once it is done.
-- check_count and next_check_at are used again for the refund: when the refund is requested they are reset, and the
-- processor sends the refund the first time and afterwards only asks the provider what became of it.
ALTER TABLE payments
    ADD COLUMN refund_reason    text
        CONSTRAINT payments_refund_reason_check CHECK (refund_reason IN
            ('booking_expired', 'seats_unavailable', 'booking_cancelled')),
    ADD COLUMN refund_reference text;

ALTER TABLE payments DROP CONSTRAINT payments_status_check;
ALTER TABLE payments ADD CONSTRAINT payments_status_check
    CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED', 'REFUND_PENDING', 'REFUNDED'));

-- A payment that was charged keeps the provider's reference of the charge through its refund.
ALTER TABLE payments DROP CONSTRAINT payments_succeeded_has_reference;
ALTER TABLE payments ADD CONSTRAINT payments_succeeded_has_reference
    CHECK (status NOT IN ('SUCCEEDED', 'REFUND_PENDING', 'REFUNDED') OR provider_reference IS NOT NULL);

-- A refund always has a reason, and a finished one has the provider's reference; nothing else has a refund.
ALTER TABLE payments ADD CONSTRAINT payments_refund_fields
    CHECK ((status IN ('REFUND_PENDING', 'REFUNDED')) = (refund_reason IS NOT NULL)
       AND (status = 'REFUNDED') = (refund_reference IS NOT NULL));

-- The processor looks for refunds that are due.
CREATE INDEX payments_refund_due_idx ON payments (next_check_at) WHERE status = 'REFUND_PENDING';
