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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * ADR-0013 test obligations 1 and 3 (and the unchanged-Authorization part of 2): the real gateway
 * on a real port, forwarding to a small stand-in for the Catalog service.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.server.port=-1")
class RoutingTest {

    private static final String UUID_REGEX =
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";

    private static final HttpServer CATALOG = startStandIn();
    private static volatile String receivedUri;
    private static volatile String receivedCorrelationId;
    private static volatile String receivedAuthorization;

    private final HttpClient client = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @DynamicPropertySource
    static void catalogAddress(DynamicPropertyRegistry registry) {
        registry.add("ticketflow.gateway.catalog-uri",
                () -> "http://127.0.0.1:" + CATALOG.getAddress().getPort());
    }

    @AfterAll
    static void stopStandIn() {
        CATALOG.stop(0);
    }

    @BeforeEach
    void forgetPreviousRequest() {
        receivedUri = null;
        receivedCorrelationId = null;
        receivedAuthorization = null;
    }

    @Test
    void forwardsTheEventListWithItsQueryString() throws Exception {
        HttpResponse<String> response = get("/api/v1/events?page=1&size=2");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("totalItems");
        assertThat(receivedUri).isEqualTo("/api/v1/events?page=1&size=2");
    }

    @Test
    void forwardsPathsBelowEvents() throws Exception {
        HttpResponse<String> response = get("/api/v1/events/123");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(receivedUri).isEqualTo("/api/v1/events/123");
    }

    @Test
    void neverRoutesInternalPaths() throws Exception {
        HttpResponse<String> response = get("/internal/v1/anything");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .contains("application/problem+json");
        assertThat(response.body()).contains("\"code\":\"ROUTE_NOT_FOUND\"");
        assertThat(receivedUri).isNull();
    }

    @Test
    void answersUnknownPathsWithoutCallingAnyService() throws Exception {
        HttpResponse<String> response = get("/api/v1/payments");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("\"code\":\"ROUTE_NOT_FOUND\"");
        assertThat(receivedUri).isNull();
    }

    @Test
    void forwardsTheCallersCorrelationId() throws Exception {
        String supplied = UUID.randomUUID().toString();

        HttpResponse<String> response = send("/api/v1/events", "X-Correlation-ID", supplied);

        assertThat(response.headers().firstValue("X-Correlation-ID")).contains(supplied);
        assertThat(receivedCorrelationId).isEqualTo(supplied);
    }

    @Test
    void createsAnIdWhenMissingAndForwardsTheSameOne() throws Exception {
        HttpResponse<String> response = get("/api/v1/events");

        String returned = response.headers().firstValue("X-Correlation-ID").orElseThrow();
        assertThat(returned).matches(UUID_REGEX);
        assertThat(receivedCorrelationId).isEqualTo(returned);
    }

    @Test
    void replacesAnInvalidIdBeforeForwarding() throws Exception {
        HttpResponse<String> response = send("/api/v1/events", "X-Correlation-ID", "not a uuid");

        String returned = response.headers().firstValue("X-Correlation-ID").orElseThrow();
        assertThat(returned).matches(UUID_REGEX);
        assertThat(receivedCorrelationId).isEqualTo(returned);
    }

    @Test
    void forwardsTheAuthorizationHeaderUnchanged() throws Exception {
        send("/api/v1/events", "Authorization", "Bearer some.token.value");

        assertThat(receivedAuthorization).isEqualTo("Bearer some.token.value");
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> send(String path, String header, String value) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .header(header, value).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** A stand-in for the Catalog service that records what the gateway sent and answers 200. */
    private static HttpServer startStandIn() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                receivedUri = exchange.getRequestURI().toString();
                receivedCorrelationId = exchange.getRequestHeaders().getFirst("X-Correlation-ID");
                receivedAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
                byte[] body = "{\"items\":[],\"page\":0,\"size\":20,\"totalItems\":0}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
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
