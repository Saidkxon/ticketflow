package com.ticketflow.booking.web;

/** One entry of the "errors" list on a validation failure (ADR-0009, part B). */
public record FieldProblem(String field, String problem) {
}
