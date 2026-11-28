package com.ticketflow.booking.payments;

/** The message is wrong in a way that trying again cannot fix. It is parked at once, without retries. */
class InvalidMessageException extends RuntimeException {

    InvalidMessageException(String message) {
        super(message);
    }
}
