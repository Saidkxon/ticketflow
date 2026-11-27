package com.ticketflow.payment.provider;

/**
 * One refund of a charge, in full (the MVP refunds everything, ADR-0006 R7). The idempotency key is the key of the
 * charge it refunds; the provider keeps charges and refunds apart, so a charge and its refund never collide. The scenario
 * is the one of the charge, so the simulator can play the same slow or doubled answer for the refund.
 */
public record RefundRequest(String idempotencyKey, long amountMinor, String currency, TestCardScenario scenario) {
}
