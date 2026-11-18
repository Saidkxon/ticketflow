package com.ticketflow.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.PlainJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The early token check at the gateway (ADR-0004, Part B), through the real filters and routes, with
 * a stand-in for Identity that serves the public keys and records what reaches it.
 * Every test uses its own client address, so the address counters do not mix.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.server.port=-1",
                "ticketflow.gateway.jwt.min-refetch-interval=0s",
                "ticketflow.gateway.limits.default-per-minute=1000",
                "ticketflow.gateway.limits.user-per-minute=3"})
class TokenCheckTest {

    private static final RSAKey KEY = TokenFixtures.newKey("key-1");
    private static final HttpServer IDENTITY = startStandIn();
    /** The key list that the stand-in serves. */
    private static volatile String publishedKeys = TokenFixtures.jwks(KEY);
    private static volatile String receivedUri;
    private static volatile String receivedMethod;
    private static volatile String receivedAuthorization;
    private static volatile int addressCounter = 0;

    private final HttpClient client = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @DynamicPropertySource
    static void identityAddress(DynamicPropertyRegistry registry) {
        String address = "http://127.0.0.1:" + IDENTITY.getAddress().getPort();
        registry.add("ticketflow.gateway.identity-uri", () -> address);
        registry.add("ticketflow.gateway.catalog-uri", () -> address);
        registry.add("ticketflow.gateway.jwt.jwks-uri", () -> address + "/.well-known/jwks.json");
    }

    @AfterAll
    static void stopStandIn() {
        IDENTITY.stop(0);
    }

    @BeforeEach
    void forgetPreviousRequest() {
        receivedUri = null;
        receivedMethod = null;
        receivedAuthorization = null;
    }

    // ---- who gets through

    @Test
    void withoutATokenTheAnswerIsAPlain401AndNoServiceIsCalled() throws Exception {
        HttpResponse<String> response = send("GET", "/api/v1/users/me", null, null);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");
        assertThat(response.body()).contains("\"code\":\"UNAUTHENTICATED\"").contains("\"correlationId\":\"");
        assertThat(receivedUri).isNull();
    }

    @Test
    void aValidTokenGoesThroughAndIsForwardedUnchanged() throws Exception {
        String token = TokenFixtures.token(KEY, UUID.randomUUID());

        HttpResponse<String> response = send("GET", "/api/v1/users/me", token, null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(receivedUri).isEqualTo("/api/v1/users/me");
        assertThat(receivedAuthorization).isEqualTo("Bearer " + token);
    }

    @Test
    void theAdminPathsAreRoutedToo() throws Exception {
        String token = TokenFixtures.token(KEY, UUID.randomUUID());
        String address = nextAddress();

        assertThat(send("GET", "/api/v1/users?email=ada", token, address).statusCode()).isEqualTo(200);
        assertThat(receivedUri).isEqualTo("/api/v1/users?email=ada");
        assertThat(send("PUT", "/api/v1/users/" + UUID.randomUUID() + "/roles", token, address).statusCode())
                .isEqualTo(200);
        assertThat(receivedUri).endsWith("/roles");
    }

    @Test
    void badTokensGetTheSamePlain401AndNoServiceIsCalled() throws Exception {
        UUID user = UUID.randomUUID();
        String[] bad = {
                TokenFixtures.sign(KEY, TokenFixtures.goodClaims(user)
                        .expirationTime(new Date(System.currentTimeMillis() - 3_600_000)).build()),
                TokenFixtures.sign(KEY, TokenFixtures.goodClaims(user).audience("some-other-api").build()),
                TokenFixtures.sign(KEY, TokenFixtures.goodClaims(user).issuer("someone-else").build()),
                TokenFixtures.token(TokenFixtures.newKey("key-1"), user),
                new PlainJWT(TokenFixtures.goodClaims(user).build()).serialize(),
                "garbage"};
        String address = nextAddress();

        for (String token : bad) {
            HttpResponse<String> response = send("GET", "/api/v1/users/me", token, address);
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.body()).contains("\"code\":\"UNAUTHENTICATED\"")
                    .doesNotContain("expired").doesNotContain("signature");
        }
        assertThat(receivedUri).isNull();
    }

    @Test
    void otherSchemesAreRefused() throws Exception {
        String token = TokenFixtures.token(KEY, UUID.randomUUID());

        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/users/me"))
                        .header("X-Forwarded-For", nextAddress())
                        .header("Authorization", "Basic " + token).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void publicPathsIgnoreAnyAuthorizationHeader() throws Exception {
        HttpResponse<String> response = send("GET", "/api/v1/events", "garbage", null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(receivedUri).isEqualTo("/api/v1/events");
    }

    @Test
    void aProtectedPathThatIsNotRoutedIs404NotForwarded() throws Exception {
        String token = TokenFixtures.token(KEY, UUID.randomUUID());

        HttpResponse<String> response = send("DELETE", "/api/v1/users/" + UUID.randomUUID(), token, null);

        assertThat(response.statusCode()).isIn(404, 405);
        assertThat(receivedUri).isNull();
    }

    // ---- the organizer's event paths (ADR-0013 route table: reads public, writes and "my events" need a token)

    @Test
    void eventWritesAndMyEventsWithoutATokenGetThePlain401AndNoServiceIsCalled() throws Exception {
        String id = UUID.randomUUID().toString();
        String address = nextAddress();

        for (String[] call : new String[][] {
                {"POST", "/api/v1/events"}, {"PUT", "/api/v1/events/" + id}, {"GET", "/api/v1/events/mine"},
                {"PUT", "/api/v1/events/" + id + "/layout"}, {"GET", "/api/v1/events/mine/" + id},
                {"GET", "/api/v1/events/mine/" + id + "/layout"}, {"POST", "/api/v1/events/" + id + "/publish"}}) {
            HttpResponse<String> response = send(call[0], call[1], null, address);

            assertThat(response.statusCode()).as(call[0] + " " + call[1]).isEqualTo(401);
            assertThat(response.body()).contains("\"code\":\"UNAUTHENTICATED\"");
        }
        assertThat(receivedUri).isNull();
    }

    @Test
    void eventWritesAndMyEventsWithATokenAreForwardedToCatalogUnchanged() throws Exception {
        String id = UUID.randomUUID().toString();
        String token = TokenFixtures.token(KEY, UUID.randomUUID());
        String address = nextAddress();

        assertThat(send("POST", "/api/v1/events", token, address).statusCode()).isEqualTo(200);
        assertThat(receivedMethod).isEqualTo("POST");
        assertThat(receivedUri).isEqualTo("/api/v1/events");
        assertThat(receivedAuthorization).isEqualTo("Bearer " + token);

        assertThat(send("PUT", "/api/v1/events/" + id, token, address).statusCode()).isEqualTo(200);
        assertThat(receivedMethod).isEqualTo("PUT");
        assertThat(receivedUri).isEqualTo("/api/v1/events/" + id);

        assertThat(send("GET", "/api/v1/events/mine", token, address).statusCode()).isEqualTo(200);
        assertThat(receivedMethod).isEqualTo("GET");
        assertThat(receivedUri).isEqualTo("/api/v1/events/mine");
    }

    @Test
    void theLayoutPathsAreForwardedToCatalogWithAToken() throws Exception {
        String id = UUID.randomUUID().toString();
        String token = TokenFixtures.token(KEY, UUID.randomUUID());
        String address = nextAddress();

        assertThat(send("PUT", "/api/v1/events/" + id + "/layout", token, address).statusCode()).isEqualTo(200);
        assertThat(receivedMethod).isEqualTo("PUT");
        assertThat(receivedUri).isEqualTo("/api/v1/events/" + id + "/layout");

        assertThat(send("GET", "/api/v1/events/mine/" + id, token, address).statusCode()).isEqualTo(200);
        assertThat(receivedUri).isEqualTo("/api/v1/events/mine/" + id);

        assertThat(send("GET", "/api/v1/events/mine/" + id + "/layout", token, address).statusCode()).isEqualTo(200);
        assertThat(receivedUri).isEqualTo("/api/v1/events/mine/" + id + "/layout");
    }

    @Test
    void publishIsForwardedToCatalogWithATokenUnchanged() throws Exception {
        String id = UUID.randomUUID().toString();
        String token = TokenFixtures.token(KEY, UUID.randomUUID());

        assertThat(send("POST", "/api/v1/events/" + id + "/publish", token, nextAddress()).statusCode()).isEqualTo(200);
        assertThat(receivedMethod).isEqualTo("POST");
        assertThat(receivedUri).isEqualTo("/api/v1/events/" + id + "/publish");
        assertThat(receivedAuthorization).isEqualTo("Bearer " + token);
    }

    @Test
    void readingEventsStaysPublic() throws Exception {
        String address = nextAddress();

        assertThat(send("GET", "/api/v1/events", null, address).statusCode()).isEqualTo(200);
        assertThat(send("GET", "/api/v1/events/" + UUID.randomUUID(), null, address).statusCode()).isEqualTo(200);
        assertThat(send("GET", "/api/v1/events/" + UUID.randomUUID() + "/layout", null, address).statusCode())
                .isEqualTo(200);
    }

    @Test
    void anEventMethodThatIsNotRoutedIsRefusedWithoutATokenAndNotForwardedWithOne() throws Exception {
        String path = "/api/v1/events/" + UUID.randomUUID();
        String token = TokenFixtures.token(KEY, UUID.randomUUID());
        String address = nextAddress();

        assertThat(send("DELETE", path, null, address).statusCode()).isEqualTo(401);
        assertThat(send("DELETE", path, token, address).statusCode()).isIn(404, 405);
        assertThat(receivedUri).isNull();
    }

    // ---- key rotation (ADR-0004, test obligation 8)

    @Test
    void aNewKeyIsPickedUpWithoutARestart() throws Exception {
        RSAKey newKey = TokenFixtures.newKey("rotated-" + UUID.randomUUID());
        String token = TokenFixtures.token(newKey, UUID.randomUUID());
        String address = nextAddress();

        // The new key is not published yet.
        assertThat(send("GET", "/api/v1/users/me", token, address).statusCode()).isEqualTo(401);

        // Identity publishes the new key next to the old one: the unknown key ID makes the gateway fetch the list again.
        publishedKeys = TokenFixtures.jwks(KEY, newKey);
        try {
            assertThat(send("GET", "/api/v1/users/me", token, address).statusCode()).isEqualTo(200);
            // Tokens of the old key still work during the rotation.
            assertThat(send("GET", "/api/v1/users/me", TokenFixtures.token(KEY, UUID.randomUUID()), address)
                    .statusCode()).isEqualTo(200);
        } finally {
            publishedKeys = TokenFixtures.jwks(KEY);
        }
    }

    // ---- per-user limit (ADR-0013, rule G3)

    @Test
    void everyUserHasTheirOwnRequestCounter() throws Exception {
        String busy = TokenFixtures.token(KEY, UUID.randomUUID());
        String other = TokenFixtures.token(KEY, UUID.randomUUID());
        String address = nextAddress();
        for (int i = 0; i < 3; i++) {
            assertThat(send("GET", "/api/v1/users/me", busy, address).statusCode()).isEqualTo(200);
        }
        receivedUri = null;

        HttpResponse<String> blocked = send("GET", "/api/v1/users/me", busy, address);

        assertThat(blocked.statusCode()).isEqualTo(429);
        assertThat(blocked.headers().firstValue("Retry-After")).isPresent();
        assertThat(blocked.body()).contains("\"code\":\"RATE_LIMITED\"");
        assertThat(receivedUri).isNull();
        // The same address, another user: not blocked. A token for the busy user from another address: blocked.
        assertThat(send("GET", "/api/v1/users/me", other, address).statusCode()).isEqualTo(200);
        assertThat(send("GET", "/api/v1/users/me", busy, nextAddress()).statusCode()).isEqualTo(429);
    }

    // ---- helpers

    private static synchronized String nextAddress() {
        addressCounter++;
        return "10.9." + (addressCounter / 250) + "." + (addressCounter % 250 + 1);
    }

    private HttpResponse<String> send(String method, String path, String token, String address) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("X-Forwarded-For", address != null ? address : nextAddress());
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        HttpRequest.BodyPublisher body = "PUT".equals(method)
                ? HttpRequest.BodyPublishers.ofString("{\"roles\":[]}") : HttpRequest.BodyPublishers.noBody();
        return client.send(request.method(method, body).build(), HttpResponse.BodyHandlers.ofString());
    }

    /** A stand-in for Identity and Catalog: serves the public keys, records what else reaches it, answers 200. */
    private static HttpServer startStandIn() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/.well-known/jwks.json", exchange -> {
                byte[] body = publishedKeys.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.createContext("/", exchange -> {
                receivedUri = exchange.getRequestURI().toString();
                receivedMethod = exchange.getRequestMethod();
                receivedAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
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
