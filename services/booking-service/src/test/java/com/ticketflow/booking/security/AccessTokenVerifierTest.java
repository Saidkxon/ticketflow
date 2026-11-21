package com.ticketflow.booking.security;

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
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** ADR-0004 test obligations 1 and 8 in the Booking service: bad tokens are refused; unsigned and HS256 tokens too. */
class AccessTokenVerifierTest {

    private final RSAKey key = TokenFixtures.newKey("key-1");
    private final AccessTokenVerifier verifier = new AccessTokenVerifier(
            new ImmutableJWKSet<>(new JWKSet(key.toPublicJWK())), TokenFixtures.ISSUER, TokenFixtures.AUDIENCE);

    private static void assertRefused(AccessTokenVerifier verifier, String token) {
        assertThatThrownBy(() -> verifier.verify(token)).isInstanceOf(InvalidAccessTokenException.class);
    }

    @Test
    void aGoodTokenIsAcceptedAndGivesTheUserAndRoles() {
        UUID user = UUID.randomUUID();

        VerifiedToken verified = verifier.verify(TokenFixtures.token(key, user));

        assertThat(verified.userId()).isEqualTo(user);
        assertThat(verified.roles()).containsExactly("CUSTOMER");
    }

    @Test
    void anExpiredTokenIsRefused() {
        Date past = new Date(System.currentTimeMillis() - 3_600_000);
        assertRefused(verifier, TokenFixtures.sign(key,
                TokenFixtures.goodClaims(UUID.randomUUID()).expirationTime(past).build()));
    }

    @Test
    void aWrongAudienceOrIssuerIsRefused() {
        assertRefused(verifier, TokenFixtures.sign(key,
                TokenFixtures.goodClaims(UUID.randomUUID()).audience("some-other-api").build()));
        assertRefused(verifier, TokenFixtures.sign(key,
                TokenFixtures.goodClaims(UUID.randomUUID()).issuer("someone-else").build()));
    }

    @Test
    void aTamperedPayloadIsRefused() {
        String[] parts = TokenFixtures.token(key, UUID.randomUUID()).split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                .replace("\"CUSTOMER\"", "\"ADMIN\"");
        String forged = parts[0] + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8))
                + "." + parts[2];

        assertThat(payload).contains("ADMIN");
        assertRefused(verifier, forged);
    }

    @Test
    void aTokenFromAnotherKeyIsRefusedEvenWithTheSameKeyId() {
        RSAKey stranger = TokenFixtures.newKey("key-1");

        assertRefused(verifier, TokenFixtures.token(stranger, UUID.randomUUID()));
        assertRefused(verifier, TokenFixtures.token(TokenFixtures.newKey("key-unknown"), UUID.randomUUID()));
    }

    @Test
    void anUnsignedTokenIsRefused() {
        assertRefused(verifier, new PlainJWT(TokenFixtures.goodClaims(UUID.randomUUID()).build()).serialize());
    }

    @Test
    void aTokenSignedWithASharedSecretInsteadOfRs256IsRefused() throws Exception {
        byte[] secret = new byte[32];
        Arrays.fill(secret, (byte) 7);
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID("key-1").build(),
                TokenFixtures.goodClaims(UUID.randomUUID()).build());
        jwt.sign(new MACSigner(secret));

        assertRefused(verifier, jwt.serialize());
    }

    @Test
    void aTokenMissingARequiredClaimIsRefused() {
        assertRefused(verifier, TokenFixtures.sign(key, new JWTClaimsSet.Builder()
                .issuer(TokenFixtures.ISSUER).audience(TokenFixtures.AUDIENCE)
                .subject(UUID.randomUUID().toString()).issueTime(new Date())
                .expirationTime(new Date(System.currentTimeMillis() + 600_000))
                .claim("roles", List.of("CUSTOMER")).build()));   // no jti
        assertRefused(verifier, TokenFixtures.sign(key,
                TokenFixtures.goodClaims(UUID.randomUUID()).subject("not-a-uuid").build()));
    }

    @Test
    void garbageAndEmptyInputAreRefused() {
        for (String garbage : new String[] {"", "   ", "not.a.token", "a.b.c", "eyJhbGciOiJSUzI1NiJ9"}) {
            assertRefused(verifier, garbage);
        }
        assertRefused(verifier, null);
    }
}
