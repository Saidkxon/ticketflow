package com.ticketflow.payment.provider;

/**
 * The provider did not answer in time. The charge may or may not have happened, so this is never a failure: the payment
 * stays pending until a lookup tells what happened (ADR-0006, rule R5).
 */
public class ProviderTimeoutException extends RuntimeException {

    public ProviderTimeoutException(String message) {
        super(message);
    }
}
