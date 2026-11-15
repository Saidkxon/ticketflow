package com.ticketflow.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

/** When Identity's keys cannot be fetched, a token cannot be checked: that is a 503, not a 401 (ADR-0013, rule G5). */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.server.port=-1",
                // Nothing listens on port 1.
                "ticketflow.gateway.jwt.jwks-uri=http://127.0.0.1:1/.well-known/jwks.json"})
class KeysUnavailableTest {

    private final HttpClient client = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    private HttpResponse<String> get(String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/users/me"))
                .header("X-Forwarded-For", "10.8.0.1");
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void aTokenCannotBeCheckedWithoutKeysSoTheAnswerIs503() throws Exception {
        HttpResponse<String> response = get(TokenFixtures.token(TokenFixtures.newKey("key-1"), UUID.randomUUID()));

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("\"code\":\"UPSTREAM_UNAVAILABLE\"").doesNotContain("127.0.0.1");
    }

    @Test
    void noTokenIsStillAPlain401() throws Exception {
        assertThat(get(null).statusCode()).isEqualTo(401);
    }

    @Test
    void publicPathsKeepWorkingWhenTheKeysAreDown() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/not-real"))
                        .header("X-Forwarded-For", "10.8.0.2")
                        .POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                HttpResponse.BodyHandlers.ofString());

        // Not routed, so 404: the point is that the token check does not get in the way.
        assertThat(response.statusCode()).isEqualTo(404);
    }
}
