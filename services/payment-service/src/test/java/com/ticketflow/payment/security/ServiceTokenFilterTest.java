package com.ticketflow.payment.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.ticketflow.payment.web.ApiException;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** The service token check in front of the internal endpoints (ADR-0004, Part D), without the rest of the application. */
class ServiceTokenFilterTest {

    private static final String PATH = "/internal/v1/payment-attempts";

    private final RSAKey key = TokenFixtures.newKey("key-1");
    private final ServiceTokenFilter filter = new ServiceTokenFilter(new ServiceTokenVerifier(
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
    void everythingUnderTheInternalPrefixNeedsAToken() {
        assertThat(ServiceTokenFilter.needsToken("/internal/v1")).isTrue();
        assertThat(ServiceTokenFilter.needsToken("/internal/v1/")).isTrue();
        assertThat(ServiceTokenFilter.needsToken(PATH)).isTrue();
        assertThat(ServiceTokenFilter.needsToken("/internal/v1/anything/new/" + UUID.randomUUID())).isTrue();
    }

    @Test
    void pathsOutsideTheInternalPrefixAreNotThisFiltersBusiness() {
        assertThat(ServiceTokenFilter.needsToken("/actuator/health")).isFalse();
        assertThat(ServiceTokenFilter.needsToken("/internal/v10/payments")).isFalse();
        assertThat(ServiceTokenFilter.needsToken("/internal")).isFalse();
        assertThat(ServiceTokenFilter.needsToken("/api/v1/payments")).isFalse();
        assertThat(ServiceTokenFilter.needsToken("/does-not-exist")).isFalse();
    }

    // ---- who gets through

    @Test
    void aValidServiceTokenIsLetThroughAndTheTokenIsOnTheRequest() throws Exception {
        Outcome outcome = run("POST", PATH,
                "Bearer " + TokenFixtures.serviceToken(key, "booking-service", "payment.attempt.create"));

        assertThat(outcome.passedOn()).isTrue();
        ServiceToken token = ServiceTokenFilter.require(outcome.request());
        assertThat(token.caller()).isEqualTo("booking-service");
        assertThat(token.allows("payment.attempt.create")).isTrue();
    }

    @Test
    void noTokenOrABadTokenGetsThePlain401() throws Exception {
        String[] attempts = {null, "Bearer garbage", "Basic abc", "Bearer ",
                "Bearer " + TokenFixtures.serviceToken(TokenFixtures.newKey("key-1"), "booking-service", "x.y"),
                // A user's token: right signature, wrong audience.
                "Bearer " + TokenFixtures.userToken(key, UUID.randomUUID(), "CUSTOMER")};

        for (String authorization : attempts) {
            Outcome outcome = run("POST", PATH, authorization);

            assertThat(outcome.response().getStatus()).isEqualTo(401);
            assertThat(outcome.response().getContentAsString()).contains("\"code\":\"UNAUTHENTICATED\"");
            assertThat(outcome.passedOn()).isFalse();
        }
    }

    @Test
    void otherPathsIgnoreAnyAuthorizationHeader() throws Exception {
        Outcome outcome = run("GET", "/actuator/health", "Bearer garbage");

        assertThat(outcome.passedOn()).isTrue();
        assertThat(outcome.request().getAttribute(ServiceTokenFilter.TOKEN_ATTRIBUTE)).isNull();
    }

    // ---- what a controller does with the token

    @Test
    void aControllerRefusesARequestThatHasNoVerifiedToken() {
        MockHttpServletRequest unchecked = new MockHttpServletRequest("POST", PATH);

        assertThatThrownBy(() -> ServiceTokenFilter.require(unchecked))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(401));
    }

    @Test
    void aValidTokenWithoutTheNeededScopeIsForbidden() throws Exception {
        Outcome outcome = run("POST", PATH, "Bearer " + TokenFixtures.serviceToken(key, "booking-service", "payment.read"));

        assertThat(outcome.passedOn()).isTrue();
        assertThat(ServiceTokenFilter.requireScope(outcome.request(), "payment.read").caller()).isEqualTo("booking-service");
        assertThatThrownBy(() -> ServiceTokenFilter.requireScope(outcome.request(), "payment.attempt.create"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status().value()).isEqualTo(403);
                    assertThat(e.code()).isEqualTo("FORBIDDEN");
                });
    }

    // ---- keys

    @Test
    void whenTheKeysCannotBeFetchedTheAnswerIs503NotA401() throws Exception {
        // Nothing listens on port 1, so fetching the keys fails, as it does when Identity is down.
        ServiceTokenFilter withoutKeys = new ServiceTokenFilter(new ServiceTokenVerifier(
                JWKSourceBuilder.<SecurityContext>create(
                        URI.create("http://127.0.0.1:1/.well-known/jwks.json").toURL(),
                        new DefaultResourceRetriever(500, 500, 51200)).build(),
                TokenFixtures.ISSUER, TokenFixtures.AUDIENCE));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", PATH);
        request.addHeader("Authorization", "Bearer " + TokenFixtures.serviceToken(key, "booking-service", "x.y"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        withoutKeys.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("\"code\":\"UPSTREAM_UNAVAILABLE\"")
                .doesNotContain("127.0.0.1");
        assertThat(chain.getRequest()).isNull();
    }
}
