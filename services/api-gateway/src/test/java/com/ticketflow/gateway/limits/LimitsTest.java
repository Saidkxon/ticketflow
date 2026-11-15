package com.ticketflow.gateway.limits;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Rate limits and the body size limit (ADR-0013, rules G3 and G7), with small numbers so the
 * tests stay short: 3 auth requests and 5 other requests per minute, bodies up to 100 bytes.
 * Every test uses its own client address (X-Forwarded-For) so the counters do not mix.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.server.port=-1",
                "ticketflow.gateway.limits.auth-per-minute=3",
                "ticketflow.gateway.limits.default-per-minute=5",
                "ticketflow.gateway.limits.max-body-size=100B"})
class LimitsTest {

    private static final HttpServer SERVICE = startStandIn();
    private static volatile String receivedUri;

    private final HttpClient client = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @DynamicPropertySource
    static void serviceAddress(DynamicPropertyRegistry registry) {
        String address = "http://127.0.0.1:" + SERVICE.getAddress().getPort();
        registry.add("ticketflow.gateway.identity-uri", () -> address);
        registry.add("ticketflow.gateway.catalog-uri", () -> address);
    }

    @AfterAll
    static void stopStandIn() {
        SERVICE.stop(0);
    }

    @BeforeEach
    void forgetPreviousRequest() {
        receivedUri = null;
    }

    // ---- rate limits

    @Test
    void authRoutesAreLimitedPerAddressAndTheServiceIsNotCalledWhenBlocked() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(login("10.3.0.1", "{}").statusCode()).isEqualTo(200);
        }
        receivedUri = null;

        HttpResponse<String> blocked = login("10.3.0.1", "{}");

        assertThat(blocked.statusCode()).isEqualTo(429);
        assertThat(blocked.headers().firstValue("Retry-After")).isPresent();
        assertThat(blocked.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");
        assertThat(blocked.body()).contains("\"code\":\"RATE_LIMITED\"").contains("\"correlationId\":\"");
        assertThat(receivedUri).isNull();
    }

    @Test
    void anotherAddressHasItsOwnCounter() throws Exception {
        for (int i = 0; i < 4; i++) {
            login("10.3.0.2", "{}");
        }

        assertThat(login("10.3.0.2", "{}").statusCode()).isEqualTo(429);
        assertThat(login("10.3.0.3", "{}").statusCode()).isEqualTo(200);
    }

    @Test
    void otherRoutesHaveTheirOwnHigherLimit() throws Exception {
        for (int i = 0; i < 4; i++) {
            login("10.3.0.4", "{}");
        }

        // The auth limit is used up, but event reads are a different group.
        for (int i = 0; i < 5; i++) {
            assertThat(events("10.3.0.4").statusCode()).isEqualTo(200);
        }
        assertThat(events("10.3.0.4").statusCode()).isEqualTo(429);
    }

    @Test
    void aMalformedForwardedAddressIsIgnoredAndCountedUnderTheRealOne() throws Exception {
        // Different garbage values must not give a fresh counter each time.
        String[] garbage = {"a b c", "<script>", "999;drop", "x".repeat(200)};
        int ok = 0;
        int blocked = 0;
        for (String value : garbage) {
            int status = login(value, "{}").statusCode();
            if (status == 200) {
                ok++;
            } else if (status == 429) {
                blocked++;
            }
        }

        assertThat(ok).isEqualTo(3);
        assertThat(blocked).isEqualTo(1);
    }

    // ---- body size

    @Test
    void aBodyAboveTheLimitIsRefusedBeforeItReachesTheService() throws Exception {
        HttpResponse<String> response = login("10.3.0.6", "x".repeat(101));

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");
        assertThat(response.body()).contains("\"code\":\"PAYLOAD_TOO_LARGE\"");
        assertThat(receivedUri).isNull();
    }

    @Test
    void aBodyExactlyAtTheLimitIsForwarded() throws Exception {
        assertThat(login("10.3.0.7", "x".repeat(100)).statusCode()).isEqualTo(200);
        assertThat(receivedUri).isEqualTo("/api/v1/auth/login");
    }

    @Test
    void aBodyOfUnknownSizeIsRefusedWith411() throws Exception {
        byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/login"))
                        .header("X-Forwarded-For", "10.3.0.8")
                        .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(411);
        assertThat(response.body()).contains("\"code\":\"LENGTH_REQUIRED\"");
        assertThat(receivedUri).isNull();
    }

    private HttpResponse<String> login(String forwardedFor, String body) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/login"))
                        .header("X-Forwarded-For", forwardedFor)
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> events(String forwardedFor) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/events"))
                        .header("X-Forwarded-For", forwardedFor).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** A stand-in for the services behind the gateway: records the path and answers 200. */
    private static HttpServer startStandIn() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                receivedUri = exchange.getRequestURI().toString();
                exchange.getRequestBody().readAllBytes();
                byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
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
