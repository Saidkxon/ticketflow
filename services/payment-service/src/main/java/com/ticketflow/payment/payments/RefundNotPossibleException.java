package com.ticketflow.payment.payments;

/** A refund was asked for that can never be made (no such payment, another booking, nothing was charged). */
public class RefundNotPossibleException extends RuntimeException {

    public RefundNotPossibleException(String message) {
        super(message);
    }
}
