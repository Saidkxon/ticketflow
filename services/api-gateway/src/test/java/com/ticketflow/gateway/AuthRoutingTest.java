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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The auth routes to the Identity service: only the four public POST paths are routed, and the
 * headers that carry the refresh cookie and its request-forgery protection pass through unchanged.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.server.port=-1")
class AuthRoutingTest {

    private static final HttpServer IDENTITY = startStandIn();
    private static volatile String receivedMethod;
    private static volatile String receivedUri;
    private static volatile String receivedCookie;
    private static volatile String receivedOrigin;
    private static volatile String receivedClientHeader;
    private static volatile String receivedBody;

    private final HttpClient client = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @DynamicPropertySource
    static void identityAddress(DynamicPropertyRegistry registry) {
        registry.add("ticketflow.gateway.identity-uri",
                () -> "http://127.0.0.1:" + IDENTITY.getAddress().getPort());
    }

    @AfterAll
    static void stopStandIn() {
        IDENTITY.stop(0);
    }

    @BeforeEach
    void forgetPreviousRequest() {
        receivedMethod = null;
        receivedUri = null;
        receivedCookie = null;
        receivedOrigin = null;
        receivedClientHeader = null;
        receivedBody = null;
    }

    @Test
    void forwardsTheFourAuthPaths() throws Exception {
        for (String action : new String[] {"register", "login", "refresh", "logout"}) {
            receivedUri = null;

            HttpResponse<String> response = post("/api/v1/auth/" + action, "{}", "10.2.0.1");

            assertThat(response.statusCode()).as(action).isEqualTo(200);
            assertThat(receivedMethod).isEqualTo("POST");
            assertThat(receivedUri).isEqualTo("/api/v1/auth/" + action);
        }
    }

    @Test
    void forwardsTheBodyAndTheCookieProtectionHeadersUnchanged() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/refresh"))
                        .header("Cookie", "refresh_token=abc123")
                        .header("Origin", "http://localhost:18090")
                        .header("X-TicketFlow-Client", "web")
                        .header("X-Forwarded-For", "10.2.0.2")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"a\":1}")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(receivedCookie).isEqualTo("refresh_token=abc123");
        assertThat(receivedOrigin).isEqualTo("http://localhost:18090");
        assertThat(receivedClientHeader).isEqualTo("web");
        assertThat(receivedBody).isEqualTo("{\"a\":1}");
    }

    @Test
    void returnsTheSetCookieHeaderOfIdentityToTheCaller() throws Exception {
        HttpResponse<String> response = post("/api/v1/auth/login", "{}", "10.2.0.3");

        assertThat(response.headers().allValues("Set-Cookie"))
                .containsExactly("refresh_token=fresh; Path=/api/v1/auth; HttpOnly; Secure; SameSite=Strict");
    }

    @Test
    void doesNotRouteOtherIdentityPathsOrMethods() throws Exception {
        assertThat(post("/api/v1/auth/not-real", "{}", "10.2.0.4").statusCode()).isEqualTo(404);
        assertThat(get("/api/v1/auth/login", "10.2.0.4").statusCode()).isIn(404, 405);
        assertThat(get("/.well-known/jwks.json", "10.2.0.4").statusCode()).isEqualTo(404);
        assertThat(get("/api/v1/users/me", "10.2.0.4").statusCode()).isEqualTo(404);
        assertThat(receivedUri).isNull();
    }

    private HttpResponse<String> post(String path, String body, String forwardedFor) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .header("X-Forwarded-For", forwardedFor)
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String forwardedFor) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .header("X-Forwarded-For", forwardedFor).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** A stand-in for the Identity service that records what the gateway sent and answers 200. */
    private static HttpServer startStandIn() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                receivedMethod = exchange.getRequestMethod();
                receivedUri = exchange.getRequestURI().toString();
                receivedCookie = exchange.getRequestHeaders().getFirst("Cookie");
                receivedOrigin = exchange.getRequestHeaders().getFirst("Origin");
                receivedClientHeader = exchange.getRequestHeaders().getFirst("X-TicketFlow-Client");
                receivedBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.getResponseHeaders().add("Set-Cookie",
                        "refresh_token=fresh; Path=/api/v1/auth; HttpOnly; Secure; SameSite=Strict");
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
