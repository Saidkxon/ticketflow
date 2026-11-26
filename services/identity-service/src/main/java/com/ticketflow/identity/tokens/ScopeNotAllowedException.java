package com.ticketflow.identity.tokens;

/** A known service asked for a target or a scope it may not have. Becomes 403 SCOPE_NOT_ALLOWED. */
public class ScopeNotAllowedException extends RuntimeException {

    public ScopeNotAllowedException() {
        super("Scope not allowed");
    }
}
