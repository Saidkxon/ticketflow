package com.ticketflow.payment.provider;

/**
 * The payment provider behind a boundary, so a real one can replace the simulator without any other code changing.
 * Both calls are safe to repeat with the same idempotency key.
 */
public interface PaymentProvider {

    /**
     * Charges once for this key; a repeat with the same key returns the first result and charges nothing more.
     *
     * @throws ProviderTimeoutException when no answer came in time; the charge may have happened
     */
    ChargeOutcome charge(ChargeRequest request);

    /**
     * Asks what became of the charge with this key. {@link ChargeState#NOT_CHARGED} means the provider confirms it holds
     * no charge for the key; {@link ChargeState#PROCESSING} means it is not decided yet.
     */
    ChargeOutcome lookup(String idempotencyKey);

    /**
     * Refunds the charge with this key in full; a repeat with the same key returns the first result and refunds
     * nothing more. A provider refuses to refund a charge it does not hold or has not finished.
     *
     * @throws ProviderTimeoutException when no answer came in time; the refund may have happened
     */
    ChargeOutcome refund(RefundRequest request);

    /**
     * Asks what became of the refund of the charge with this key. {@link ChargeState#NOT_CHARGED} here means the
     * provider confirms that it holds no refund for the key (it is safe to ask for it again); {@link
     * ChargeState#PROCESSING} means it is not decided yet.
     */
    ChargeOutcome lookupRefund(String idempotencyKey);
}
