package com.ticketflow.payment.provider;

/**
 * What the simulated provider does with a payment. The customer picks it by typing a test card number, like the test
 * cards of real providers. Only the scenario and the last four digits are kept, never the number.
 */
public enum TestCardScenario {
    /** 4242 4242 4242 4242: the charge succeeds at once. */
    SUCCESS,
    /** 4000 0000 0000 0002: the provider declines the card. */
    DECLINED,
    /** 4000 0000 0000 0259: the provider times out, then the charge turns out to have succeeded, minutes later. */
    TIMEOUT_THEN_SUCCESS,
    /** 4000 0000 0000 0077: the charge succeeds and the provider reports the success twice. */
    DOUBLE_REPORT,
    /** No card was given. */
    NO_PAYMENT_METHOD;

    /** Any number that is not one of the test cards is declined, like a real card that is not valid. */
    public static TestCardScenario forCard(String number) {
        if (number == null) {
            return NO_PAYMENT_METHOD;
        }
        return switch (number) {
            case "4242424242424242" -> SUCCESS;
            case "4000000000000259" -> TIMEOUT_THEN_SUCCESS;
            case "4000000000000077" -> DOUBLE_REPORT;
            default -> DECLINED;
        };
    }
}
