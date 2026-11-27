-- The result of a payment attempt, and the simulated provider (ADR-0006, rules R4 and R5).
--
-- scenario and card_last4: which test card the customer chose. The card number itself is never stored; the last four
-- digits are only there so support can tell two attempts apart. Old rows get NO_PAYMENT_METHOD.
-- provider_reference and failure_reason: how the provider answered. A failed payment always has a reason, a succeeded
-- one always has the provider's reference: the database refuses anything else.
-- check_count and next_check_at: the processor asks the provider about a pending payment when next_check_at has
-- passed. A timeout leaves the payment PENDING and moves next_check_at forward; it is never turned into a failure
-- until the provider confirms that it did not charge (R5).
ALTER TABLE payments
    ADD COLUMN scenario           text        NOT NULL DEFAULT 'NO_PAYMENT_METHOD'
        CONSTRAINT payments_scenario_check CHECK (scenario IN
            ('SUCCESS', 'DECLINED', 'TIMEOUT_THEN_SUCCESS', 'DOUBLE_REPORT', 'NO_PAYMENT_METHOD')),
    ADD COLUMN card_last4         text
        CONSTRAINT payments_card_last4_check CHECK (card_last4 ~ '^[0-9]{4}$'),
    ADD COLUMN provider_reference text,
    ADD COLUMN failure_reason     text
        CONSTRAINT payments_failure_reason_check CHECK (failure_reason ~ '^[a-z_]{1,64}$'),
    ADD COLUMN check_count        integer     NOT NULL DEFAULT 0 CHECK (check_count >= 0),
    ADD COLUMN next_check_at      timestamptz NOT NULL DEFAULT clock_timestamp(),
    ADD CONSTRAINT payments_failed_has_reason CHECK ((status = 'FAILED') = (failure_reason IS NOT NULL)),
    ADD CONSTRAINT payments_succeeded_has_reference CHECK (status <> 'SUCCEEDED' OR provider_reference IS NOT NULL);

-- The processor looks for pending payments that are due.
CREATE INDEX payments_pending_due_idx ON payments (next_check_at) WHERE status = 'PENDING';

-- What the simulated provider knows, keyed like a real provider keys charges: by idempotency key. visible_at is when
-- the result becomes visible (later than the charge for the timeout scenario). This table belongs to the simulator and
-- is dropped when a real provider replaces it.
CREATE TABLE sim_provider_charges (
    idempotency_key text        PRIMARY KEY,
    scenario        text        NOT NULL,
    reference       text        NOT NULL,
    amount_minor    bigint      NOT NULL,
    currency        text        NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT clock_timestamp(),
    visible_at      timestamptz NOT NULL
);
