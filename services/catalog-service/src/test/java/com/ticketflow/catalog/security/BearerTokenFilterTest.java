package com.ticketflow.catalog.security;

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

/** The token and role check in front of the Catalog endpoints (ADR-0004), without the rest of the application. */
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

    // ---- which requests need an organizer

    @Test
    void plainReadsOfTheEventListArePublicAndEverythingElseUnderEventsIsNot() {
        assertThat(BearerTokenFilter.needsOrganizer("GET", "/api/v1/events")).isFalse();
        assertThat(BearerTokenFilter.needsOrganizer("HEAD", "/api/v1/events")).isFalse();
        assertThat(BearerTokenFilter.needsOrganizer("GET", "/api/v1/events/" + UUID.randomUUID())).isFalse();

        assertThat(BearerTokenFilter.needsOrganizer("GET", "/api/v1/events/mine")).isTrue();
        assertThat(BearerTokenFilter.needsOrganizer("POST", "/api/v1/events")).isTrue();
        assertThat(BearerTokenFilter.needsOrganizer("PUT", "/api/v1/events/" + UUID.randomUUID())).isTrue();
        assertThat(BearerTokenFilter.needsOrganizer("DELETE", "/api/v1/events/" + UUID.randomUUID())).isTrue();
        assertThat(BearerTokenFilter.needsOrganizer("PATCH", "/api/v1/events/" + UUID.randomUUID())).isTrue();
        assertThat(BearerTokenFilter.needsOrganizer("OPTIONS", "/api/v1/events")).isTrue();
    }

    @Test
    void otherPathsAreNotThisFiltersBusiness() {
        assertThat(BearerTokenFilter.needsOrganizer("POST", "/api/v1/users")).isFalse();
        assertThat(BearerTokenFilter.needsOrganizer("POST", "/api/v1/eventsfoo")).isFalse();
    }

    // ---- who gets through

    @Test
    void anOrganizerIsLetThroughAndTheTokenIsOnTheRequest() throws Exception {
        UUID user = UUID.randomUUID();

        Outcome outcome = run("POST", "/api/v1/events", "Bearer " + TokenFixtures.token(key, user, "CUSTOMER", "ORGANIZER"));

        assertThat(outcome.passedOn()).isTrue();
        VerifiedToken token = (VerifiedToken) outcome.request().getAttribute(BearerTokenFilter.TOKEN_ATTRIBUTE);
        assertThat(token.userId()).isEqualTo(user);
    }

    @Test
    void aCustomerGets403AndNothingIsCalled() throws Exception {
        Outcome outcome = run("POST", "/api/v1/events",
                "Bearer " + TokenFixtures.token(key, UUID.randomUUID(), "CUSTOMER"));

        assertThat(outcome.response().getStatus()).isEqualTo(403);
        assertThat(outcome.response().getContentAsString()).contains("\"code\":\"FORBIDDEN\"");
        assertThat(outcome.passedOn()).isFalse();
    }

    @Test
    void anAdminWithoutTheOrganizerRoleIsRefusedToo() throws Exception {
        Outcome outcome = run("PUT", "/api/v1/events/" + UUID.randomUUID(),
                "Bearer " + TokenFixtures.token(key, UUID.randomUUID(), "CUSTOMER", "ADMIN"));

        assertThat(outcome.response().getStatus()).isEqualTo(403);
    }

    @Test
    void noTokenOrABadTokenGetsThePlain401() throws Exception {
        String[] attempts = {null, "Bearer garbage", "Basic abc", "Bearer ",
                "Bearer " + TokenFixtures.token(TokenFixtures.newKey("key-1"), UUID.randomUUID(), "ORGANIZER")};

        for (String authorization : attempts) {
            Outcome outcome = run("POST", "/api/v1/events", authorization);

            assertThat(outcome.response().getStatus()).isEqualTo(401);
            assertThat(outcome.response().getContentAsString()).contains("\"code\":\"UNAUTHENTICATED\"");
            assertThat(outcome.passedOn()).isFalse();
        }
    }

    @Test
    void myEventsNeedsAnOrganizerEvenThoughItIsARead() throws Exception {
        assertThat(run("GET", "/api/v1/events/mine", null).response().getStatus()).isEqualTo(401);
        assertThat(run("GET", "/api/v1/events/mine",
                "Bearer " + TokenFixtures.token(key, UUID.randomUUID(), "CUSTOMER")).response().getStatus())
                .isEqualTo(403);
    }

    @Test
    void thePublicListIgnoresAnyAuthorizationHeader() throws Exception {
        Outcome outcome = run("GET", "/api/v1/events", "Bearer garbage");

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
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/events");
        request.addHeader("Authorization", "Bearer " + TokenFixtures.token(key, UUID.randomUUID(), "ORGANIZER"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        withoutKeys.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("\"code\":\"UPSTREAM_UNAVAILABLE\"")
                .doesNotContain("127.0.0.1");
        assertThat(chain.getRequest()).isNull();
    }
}
