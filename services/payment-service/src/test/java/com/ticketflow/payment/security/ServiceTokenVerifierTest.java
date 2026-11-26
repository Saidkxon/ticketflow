package com.ticketflow.payment.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * ADR-0004 test obligations 1, 7 and 8 in the Payment service: bad tokens are refused (unsigned and HS256 too), and a
 * user's access token is not a service token.
 */
class ServiceTokenVerifierTest {

    private static final String SCOPE = "payment.attempt.create";

    private final RSAKey key = TokenFixtures.newKey("key-1");
    private final ServiceTokenVerifier verifier = new ServiceTokenVerifier(
            new ImmutableJWKSet<>(new JWKSet(key.toPublicJWK())), TokenFixtures.ISSUER, TokenFixtures.AUDIENCE);

    private static void assertRefused(ServiceTokenVerifier verifier, String token) {
        assertThatThrownBy(() -> verifier.verify(token)).isInstanceOf(InvalidAccessTokenException.class);
    }

    @Test
    void aGoodTokenIsAcceptedAndGivesTheCallerAndTheScopes() {
        ServiceToken verified = verifier.verify(TokenFixtures.serviceToken(key, "booking-service", SCOPE, "payment.read"));

        assertThat(verified.caller()).isEqualTo("booking-service");
        assertThat(verified.scopes()).containsExactlyInAnyOrder(SCOPE, "payment.read");
        assertThat(verified.allows(SCOPE)).isTrue();
        assertThat(verified.allows("payment.refund.create")).isFalse();
    }

    @Test
    void anExpiredTokenIsRefused() {
        Date past = new Date(System.currentTimeMillis() - 3_600_000);
        assertRefused(verifier, TokenFixtures.sign(key,
                TokenFixtures.goodClaims("booking-service", SCOPE).expirationTime(past).build()));
    }

    @Test
    void aWrongAudienceOrIssuerIsRefused() {
        assertRefused(verifier, TokenFixtures.sign(key,
                TokenFixtures.goodClaims("booking-service", SCOPE).audience("notification-service").build()));
        assertRefused(verifier, TokenFixtures.sign(key,
                TokenFixtures.goodClaims("booking-service", SCOPE).issuer("someone-else").build()));
    }

    @Test
    void aUsersAccessTokenIsRefusedEvenIfItsSignatureIsValid() {
        // The audience of a user token is the public API.
        assertRefused(verifier, TokenFixtures.userToken(key, UUID.randomUUID(), "CUSTOMER"));
        // Even a token that names this service as audience is refused when it carries roles: that is a user token.
        assertRefused(verifier, TokenFixtures.sign(key, TokenFixtures.goodClaims("booking-service", SCOPE)
                .claim("roles", java.util.List.of("ADMIN")).build()));
    }

    @Test
    void aTamperedPayloadIsRefused() {
        String[] parts = TokenFixtures.serviceToken(key, "booking-service", SCOPE).split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                .replace(SCOPE, "payment.refund.create");
        String forged = parts[0] + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8))
                + "." + parts[2];

        assertThat(payload).contains("payment.refund.create");
        assertRefused(verifier, forged);
    }

    @Test
    void aTokenFromAnotherKeyIsRefusedEvenWithTheSameKeyId() {
        RSAKey stranger = TokenFixtures.newKey("key-1");

        assertRefused(verifier, TokenFixtures.serviceToken(stranger, "booking-service", SCOPE));
        assertRefused(verifier, TokenFixtures.serviceToken(TokenFixtures.newKey("key-unknown"), "booking-service", SCOPE));
    }

    @Test
    void anUnsignedTokenIsRefused() {
        assertRefused(verifier, new PlainJWT(TokenFixtures.goodClaims("booking-service", SCOPE).build()).serialize());
    }

    @Test
    void aTokenSignedWithASharedSecretInsteadOfRs256IsRefused() throws Exception {
        byte[] secret = new byte[32];
        Arrays.fill(secret, (byte) 7);
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID("key-1").build(),
                TokenFixtures.goodClaims("booking-service", SCOPE).build());
        jwt.sign(new MACSigner(secret));

        assertRefused(verifier, jwt.serialize());
    }

    @Test
    void aTokenMissingARequiredClaimIsRefused() {
        assertRefused(verifier, TokenFixtures.sign(key, new JWTClaimsSet.Builder()
                .issuer(TokenFixtures.ISSUER).audience(TokenFixtures.AUDIENCE)
                .subject("booking-service").issueTime(new Date())
                .expirationTime(new Date(System.currentTimeMillis() + 600_000))
                .claim("scope", SCOPE).build()));   // no jti
        assertRefused(verifier, TokenFixtures.sign(key, new JWTClaimsSet.Builder()
                .issuer(TokenFixtures.ISSUER).audience(TokenFixtures.AUDIENCE)
                .subject("booking-service").issueTime(new Date())
                .expirationTime(new Date(System.currentTimeMillis() + 600_000))
                .jwtID(UUID.randomUUID().toString()).build()));   // no scope
    }

    @Test
    void anEmptyScopeOrAnUnusualCallerNameIsRefused() {
        assertRefused(verifier, TokenFixtures.sign(key, TokenFixtures.goodClaims("booking-service", "  ").build()));
        assertRefused(verifier, TokenFixtures.serviceToken(key, "9-service", SCOPE));
        assertRefused(verifier, TokenFixtures.serviceToken(key, "booking_service", SCOPE));
        assertRefused(verifier, TokenFixtures.serviceToken(key, "Booking Service", SCOPE));
    }

    @Test
    void garbageAndEmptyInputAreRefused() {
        for (String garbage : new String[] {"", "   ", "not.a.token", "a.b.c", "eyJhbGciOiJSUzI1NiJ9"}) {
            assertRefused(verifier, garbage);
        }
        assertRefused(verifier, null);
    }
}
