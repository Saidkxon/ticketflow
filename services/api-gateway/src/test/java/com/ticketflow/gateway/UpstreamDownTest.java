package com.ticketflow.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** ADR-0013 test obligation 5 (first half): a service that is down gives 503 without internal names. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.server.port=-1")
class UpstreamDownTest {

    /** A port that nothing listens on: it was free a moment ago and is closed again. */
    private static final int CLOSED_PORT = closedPort();

    @Value("${local.server.port}")
    private int port;

    @DynamicPropertySource
    static void catalogAddress(DynamicPropertyRegistry registry) {
        registry.add("ticketflow.gateway.catalog-uri", () -> "http://127.0.0.1:" + CLOSED_PORT);
    }

    @Test
    void answersUnavailableWithoutLeakingInternalDetails() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/events")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .contains("application/problem+json");
        assertThat(response.body())
                .contains("\"code\":\"UPSTREAM_UNAVAILABLE\"")
                .contains("\"correlationId\":\"" + response.headers().firstValue("X-Correlation-ID").orElseThrow() + "\"")
                .doesNotContain("127.0.0.1")
                .doesNotContain(String.valueOf(CLOSED_PORT))
                .doesNotContain("Exception");
    }

    private static int closedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
