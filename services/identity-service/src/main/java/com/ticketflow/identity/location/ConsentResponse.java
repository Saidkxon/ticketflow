package com.ticketflow.identity.location;

import java.time.Instant;

/**
 * The stored answer. level, textVersion and decidedAt are empty until the person answers.
 * needsAnswer is true when there is no answer yet or it was given for an older text.
 */
public record ConsentResponse(LocationLevel level, Integer textVersion, Instant decidedAt,
        int currentTextVersion, boolean needsAnswer) {
}
