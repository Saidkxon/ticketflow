package com.ticketflow.payment.provider;

/** What a payment provider says about one charge (ADR-0006, rule R5). */
public enum ChargeState {
    /** The money was taken. */
    SUCCEEDED,
    /** The provider refused the charge. Nothing was taken. */
    DECLINED,
    /** The provider has not decided yet, or its answer has not reached us. The charge may still happen. */
    PROCESSING,
    /** The provider has no charge for this key, so nothing was taken. Only a lookup answers this. */
    NOT_CHARGED
}
