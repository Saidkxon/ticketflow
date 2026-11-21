package com.ticketflow.booking.provisioning;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Fetches the layout snapshot from Catalog (ADR-0012, part A). Only used while an event is being set up: selling
 * never calls Catalog. Any answer but 200 is a failure that is tried again later, because the cause (Catalog starting
 * or down) usually passes.
 */
@Component
class CatalogClient {

    /** A published layout has at most about 5,000 seats, well below 1 MB of JSON; anything bigger is not ours. */
    static final int MAX_BODY_CHARS = 5_000_000;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String baseUri;

    CatalogClient(ProvisioningProperties properties) {
        String base = properties.catalogUri();
        this.baseUri = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    /** The layout JSON of the event. Throws {@link CatalogUnavailableException} when it cannot be fetched now. */
    String fetchLayout(UUID eventId, String correlationId) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUri + "/api/v1/events/" + eventId + "/layout"))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .GET();
        if (correlationId != null) {
            request.header("X-Correlation-ID", correlationId);
        }
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new CatalogUnavailableException("Catalog answered " + response.statusCode());
            }
            if (response.body().length() > MAX_BODY_CHARS) {
                throw new InvalidMessageException("the layout is larger than expected");
            }
            return response.body();
        } catch (IOException e) {
            // Only the kind of failure is kept: the message text of the exception may name internal addresses.
            throw new CatalogUnavailableException("Catalog is not reachable (" + e.getClass().getSimpleName() + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CatalogUnavailableException("interrupted while waiting for Catalog");
        }
    }

    static class CatalogUnavailableException extends RuntimeException {
        CatalogUnavailableException(String message) {
            super(message);
        }
    }
}
