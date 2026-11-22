package com.ticketflow.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Seat availability is Booking's, even though its path is below /api/v1/events, which goes to Catalog (ADR-0011,
 * ADR-0013). Two stand-ins, one for each service, tell the two apart. It is public and conditional requests work.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.server.port=-1")
class AvailabilityRoutingTest {

    private static final HttpServer CATALOG = startStandIn("catalog");
    private static final HttpServer BOOKING = startStandIn("booking");

    private final HttpClient client = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @DynamicPropertySource
    static void serviceAddresses(DynamicPropertyRegistry registry) {
        registry.add("ticketflow.gateway.catalog-uri", () -> "http://127.0.0.1:" + CATALOG.getAddress().getPort());
        registry.add("ticketflow.gateway.booking-uri", () -> "http://127.0.0.1:" + BOOKING.getAddress().getPort());
    }

    @AfterAll
    static void stopStandIns() {
        CATALOG.stop(0);
        BOOKING.stop(0);
    }

    @Test
    void availabilityGoesToBookingAndNeedsNoToken() throws Exception {
        HttpResponse<String> response = get("/api/v1/events/" + UUID.randomUUID() + "/availability", null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("booking");
    }

    @Test
    void availabilityIgnoresABadAuthorizationHeader() throws Exception {
        HttpResponse<String> response = get("/api/v1/events/" + UUID.randomUUID() + "/availability", "Bearer garbage");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("booking");
    }

    @Test
    void otherEventPathsStillGoToCatalog() throws Exception {
        String event = UUID.randomUUID().toString();

        assertThat(get("/api/v1/events/" + event, null).body()).isEqualTo("catalog");
        assertThat(get("/api/v1/events/" + event + "/layout", null).body()).isEqualTo("catalog");
        assertThat(get("/api/v1/events", null).body()).isEqualTo("catalog");
    }

    @Test
    void aConditionalRequestComesBackAs304WithTheETag() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/events/"
                                + UUID.randomUUID() + "/availability"))
                        .header("If-None-Match", "\"v1\"").GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(304);
        assertThat(response.headers().firstValue("ETag")).contains("\"v1\"");
    }

    @Test
    void aWriteToAvailabilityIsRefusedAtTheGateway() throws Exception {
        // Under /api/v1/events every request but a plain read needs a token, and there is no write route for this path.
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/events/"
                                + UUID.randomUUID() + "/availability"))
                        .POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).doesNotContain("booking").doesNotContain("catalog");
    }

    private HttpResponse<String> get(String path, String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET();
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Answers 200 with its own name as the body, or 304 with an ETag when the request is conditional. */
    private static HttpServer startStandIn(String name) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String conditional = exchange.getRequestHeaders().getFirst("If-None-Match");
                if (conditional != null) {
                    exchange.getResponseHeaders().add("ETag", conditional);
                    exchange.sendResponseHeaders(304, -1);
                    exchange.close();
                    return;
                }
                byte[] body = name.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/plain");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
