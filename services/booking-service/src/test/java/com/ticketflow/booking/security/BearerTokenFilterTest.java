package com.ticketflow.booking.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** The token check in front of the Booking endpoints (ADR-0004), without the rest of the application. */
class BearerTokenFilterTest {

    private final RSAKey key = TokenFixtures.newKey("key-1");
    private final BearerTokenFilter filter = new BearerTokenFilter(new AccessTokenVerifier(
            new ImmutableJWKSet<>(new JWKSet(key.toPublicJWK())), TokenFixtures.ISSUER, TokenFixtures.AUDIENCE));

    /** What the filter did: the response, and the chain, which is only called when the request is let through. */
    private record Outcome(MockHttpServletResponse response, MockFilterChain chain, MockHttpServletRequest request) {
        boolean passedOn() {
            return chain.getRequest() != null;
        }
    }

    private Outcome run(String method, String path, String authorization) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return new Outcome(response, chain, request);
    }

    // ---- which requests need a token

    @Test
    void onlyAPlainReadOfAvailabilityIsPublic() {
        String event = UUID.randomUUID().toString();

        assertThat(BearerTokenFilter.needsToken("GET", "/api/v1/events/" + event + "/availability")).isFalse();
        assertThat(BearerTokenFilter.needsToken("HEAD", "/api/v1/events/" + event + "/availability")).isFalse();

        assertThat(BearerTokenFilter.needsToken("POST", "/api/v1/events/" + event + "/availability")).isTrue();
        assertThat(BearerTokenFilter.needsToken("POST", "/api/v1/events/" + event + "/holds")).isTrue();
        assertThat(BearerTokenFilter.needsToken("GET", "/api/v1/events/" + event + "/holds")).isTrue();
        assertThat(BearerTokenFilter.needsToken("GET", "/api/v1/bookings")).isTrue();
        assertThat(BearerTokenFilter.needsToken("GET", "/api/v1/bookings/" + UUID.randomUUID())).isTrue();
        assertThat(BearerTokenFilter.needsToken("DELETE", "/api/v1/bookings/" + UUID.randomUUID())).isTrue();
        assertThat(BearerTokenFilter.needsToken("GET", "/api/v1/anything-new")).isTrue();
        assertThat(BearerTokenFilter.needsToken("GET", "/api/v1")).isTrue();
    }

    @Test
    void oddSpellingsOfTheAvailabilityPathAreNotPublic() {
        assertThat(BearerTokenFilter.needsToken("GET", "/api/v1/events//availability")).isTrue();
        assertThat(BearerTokenFilter.needsToken("GET", "/api/v1/events/a/b/availability")).isTrue();
        assertThat(BearerTokenFilter.needsToken("GET", "/api/v1/events/abc/availability/extra")).isTrue();
        assertThat(BearerTokenFilter.needsToken("GET", "/api/v1/events/abc/availabilityx")).isTrue();
    }

    @Test
    void pathsOutsideTheApiAreNotThisFiltersBusiness() {
        assertThat(BearerTokenFilter.needsToken("GET", "/actuator/health")).isFalse();
        assertThat(BearerTokenFilter.needsToken("GET", "/api/v10/bookings")).isFalse();
        assertThat(BearerTokenFilter.needsToken("GET", "/does-not-exist")).isFalse();
    }

    // ---- who gets through

    @Test
    void anyValidUserIsLetThroughAndTheTokenIsOnTheRequest() throws Exception {
        UUID user = UUID.randomUUID();

        Outcome outcome = run("GET", "/api/v1/bookings", "Bearer " + TokenFixtures.token(key, user, "CUSTOMER"));

        assertThat(outcome.passedOn()).isTrue();
        VerifiedToken token = (VerifiedToken) outcome.request().getAttribute(BearerTokenFilter.TOKEN_ATTRIBUTE);
        assertThat(token.userId()).isEqualTo(user);
    }

    @Test
    void noTokenOrABadTokenGetsThePlain401() throws Exception {
        String[] attempts = {null, "Bearer garbage", "Basic abc", "Bearer ",
                "Bearer " + TokenFixtures.token(TokenFixtures.newKey("key-1"), UUID.randomUUID(), "CUSTOMER")};

        for (String authorization : attempts) {
            Outcome outcome = run("GET", "/api/v1/bookings", authorization);

            assertThat(outcome.response().getStatus()).isEqualTo(401);
            assertThat(outcome.response().getContentAsString()).contains("\"code\":\"UNAUTHENTICATED\"");
            assertThat(outcome.passedOn()).isFalse();
        }
    }

    @Test
    void availabilityIgnoresAnyAuthorizationHeader() throws Exception {
        Outcome outcome = run("GET", "/api/v1/events/" + UUID.randomUUID() + "/availability", "Bearer garbage");

        assertThat(outcome.passedOn()).isTrue();
        assertThat(outcome.request().getAttribute(BearerTokenFilter.TOKEN_ATTRIBUTE)).isNull();
    }

    // ---- keys

    @Test
    void whenTheKeysCannotBeFetchedTheAnswerIs503NotA401() throws Exception {
        // Nothing listens on port 1, so fetching the keys fails, as it does when Identity is down.
        BearerTokenFilter withoutKeys = new BearerTokenFilter(new AccessTokenVerifier(
                JWKSourceBuilder.<SecurityContext>create(
                        URI.create("http://127.0.0.1:1/.well-known/jwks.json").toURL(),
                        new DefaultResourceRetriever(500, 500, 51200)).build(),
                TokenFixtures.ISSUER, TokenFixtures.AUDIENCE));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/bookings");
        request.addHeader("Authorization", "Bearer " + TokenFixtures.token(key, UUID.randomUUID(), "CUSTOMER"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        withoutKeys.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("\"code\":\"UPSTREAM_UNAVAILABLE\"")
                .doesNotContain("127.0.0.1");
        assertThat(chain.getRequest()).isNull();
    }
}
