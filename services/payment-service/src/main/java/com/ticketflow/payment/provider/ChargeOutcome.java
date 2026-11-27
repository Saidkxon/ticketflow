package com.ticketflow.payment.provider;

/**
 * The provider's answer about one charge.
 *
 * @param state      what happened
 * @param reference  the provider's own reference for a successful charge, otherwise null
 * @param reason     for a refusal, a short code from {@link #CARD_DECLINED} and the constants next to it, otherwise null
 * @param deliveries how many times the provider reports this result; a provider may report the same result twice, and
 *                   the code that applies results must cope with that (ADR-0006, rule R4)
 */
public record ChargeOutcome(ChargeState state, String reference, String reason, int deliveries) {

    public static final String CARD_DECLINED = "card_declined";
    public static final String NO_PAYMENT_METHOD = "no_payment_method";
    /** The provider refused a refund because it holds no finished, successful charge for the key. */
    public static final String NO_CHARGE_TO_REFUND = "no_charge_to_refund";
    /** The provider confirmed by a lookup that it never charged: the only way a timeout becomes a failure (R5). */
    public static final String PROVIDER_NO_CHARGE = "provider_no_charge";

    public static ChargeOutcome succeeded(String reference, int deliveries) {
        return new ChargeOutcome(ChargeState.SUCCEEDED, reference, null, deliveries);
    }

    public static ChargeOutcome declined(String reason) {
        return new ChargeOutcome(ChargeState.DECLINED, null, reason, 1);
    }

    public static ChargeOutcome processing() {
        return new ChargeOutcome(ChargeState.PROCESSING, null, null, 1);
    }

    public static ChargeOutcome notCharged() {
        return new ChargeOutcome(ChargeState.NOT_CHARGED, null, PROVIDER_NO_CHARGE, 1);
    }
}
