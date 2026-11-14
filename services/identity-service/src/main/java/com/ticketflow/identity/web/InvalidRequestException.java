package com.ticketflow.identity.web;

import java.util.List;

/** Thrown when input is invalid; becomes a 400 problem with code INVALID_REQUEST and an "errors" list. */
public class InvalidRequestException extends RuntimeException {

    private final List<FieldProblem> errors;

    public InvalidRequestException(List<FieldProblem> errors) {
        super("Invalid request");
        this.errors = List.copyOf(errors);
    }

    public List<FieldProblem> errors() {
        return errors;
    }
}
