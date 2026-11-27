package com.ticketflow.payment.provider;

/**
 * One charge. The idempotency key makes the call safe to repeat: the provider charges once per key, however often it is
 * asked. No card number is part of it: the simulator is told which test scenario to play, a real provider would be
 * given a token made by the provider's own form.
 */
public record ChargeRequest(String idempotencyKey, long amountMinor, String currency, TestCardScenario scenario) {
}
