package com.ticketflow.identity.tokens;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.ticketflow.identity.users.Role;
import com.ticketflow.identity.users.User;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * ADR-0004: the token carries only what it must, is signed with the current key, and stays
 * verifiable through the published keys, also across a key rotation (test obligation 8).
 * Tokens are checked here with the Nimbus library directly, independent of our own code.
 */
class AccessTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-14T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @TempDir
    Path dir;

    private AccessTokenService serviceSigningWith(String signingKeyId) {
        SigningKeys keys = SigningKeys.load(dir.toString(), signingKeyId);
        JwtProperties properties = new JwtProperties(
                "test-issuer", "test-audience", Duration.ofMinutes(15), dir.toString(), signingKeyId);
        return new AccessTokenService(new NimbusJwtEncoder(keys.encoderSource()), keys, properties, CLOCK);
    }

    private static User user(Role... roles) {
        return new User(UUID.randomUUID(), "ada@example.com", "{bcrypt}hash", "Ada Lovelace", Set.of(roles));
    }

    private JWKSet published(String signingKeyId) throws Exception {
        return JWKSet.parse(SigningKeys.load(dir.toString(), signingKeyId).publicJwks());
    }

    private static boolean signatureIsValid(String token, JWKSet keys) throws Exception {
        SignedJWT jwt = SignedJWT.parse(token);
        JWK key = keys.getKeyByKeyId(jwt.getHeader().getKeyID());
        return key != null && jwt.verify(new RSASSAVerifier(key.toRSAKey()));
    }

    @Test
    void theTokenCarriesOnlyTheExpectedClaims() throws Exception {
        TestKeys.write(dir, "key-1");
        User user = user(Role.CUSTOMER, Role.ORGANIZER);

        IssuedToken issued = serviceSigningWith("key-1").issue(user);

        JWTClaimsSet claims = SignedJWT.parse(issued.value()).getJWTClaimsSet();
        assertThat(claims.getIssuer()).isEqualTo("test-issuer");
        assertThat(claims.getAudience()).containsExactly("test-audience");
        assertThat(claims.getSubject()).isEqualTo(user.getId().toString());
        assertThat(claims.getStringListClaim("roles")).containsExactly("CUSTOMER", "ORGANIZER");
        assertThat(claims.getIssueTime().toInstant()).isEqualTo(NOW);
        assertThat(claims.getExpirationTime().toInstant()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
        assertThat(claims.getJWTID()).isNotBlank();
        // No personal data in the token (ADR-0004).
        assertThat(claims.getClaims().keySet())
                .containsExactlyInAnyOrder("iss", "aud", "sub", "roles", "iat", "exp", "jti");
        assertThat(issued.expiresInSeconds()).isEqualTo(900);
    }

    @Test
    void everyTokenHasItsOwnId() throws Exception {
        TestKeys.write(dir, "key-1");
        AccessTokenService service = serviceSigningWith("key-1");
        User user = user(Role.CUSTOMER);

        String first = SignedJWT.parse(service.issue(user).value()).getJWTClaimsSet().getJWTID();
        String second = SignedJWT.parse(service.issue(user).value()).getJWTClaimsSet().getJWTID();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void theTokenIsSignedWithRs256AndTheCurrentKeyId() throws Exception {
        TestKeys.write(dir, "key-1");

        String token = serviceSigningWith("key-1").issue(user(Role.CUSTOMER)).value();

        SignedJWT jwt = SignedJWT.parse(token);
        assertThat(jwt.getHeader().getAlgorithm().getName()).isEqualTo("RS256");
        assertThat(jwt.getHeader().getKeyID()).isEqualTo("key-1");
        assertThat(signatureIsValid(token, published("key-1"))).isTrue();
    }

    @Test
    void aTokenWithChangedContentFailsTheSignatureCheck() throws Exception {
        TestKeys.write(dir, "key-1");
        String token = serviceSigningWith("key-1").issue(user(Role.CUSTOMER)).value();
        String[] parts = token.split("\\.");
        String forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"sub\":\"" + UUID.randomUUID() + "\",\"roles\":[\"ADMIN\"]}").getBytes());

        String forged = parts[0] + "." + forgedPayload + "." + parts[2];

        assertThat(signatureIsValid(forged, published("key-1"))).isFalse();
    }

    @Test
    void aTokenSignedByAnotherKeyIsNotValid() throws Exception {
        TestKeys.write(dir, "key-1");
        String token = serviceSigningWith("key-1").issue(user(Role.CUSTOMER)).value();

        Path elsewhere = dir.resolve("other");
        java.nio.file.Files.createDirectory(elsewhere);
        TestKeys.write(elsewhere, "key-1"); // same key ID, different key
        JWKSet strangerKeys = JWKSet.parse(SigningKeys.load(elsewhere.toString(), "key-1").publicJwks());

        assertThat(signatureIsValid(token, strangerKeys)).isFalse();
    }

    @Test
    void rotatingTheKeyKeepsOldTokensValidAndSignsNewOnesWithTheNewKey() throws Exception {
        TestKeys.write(dir, "key-old");
        String oldToken = serviceSigningWith("key-old").issue(user(Role.CUSTOMER)).value();

        // Rotation: the new key is added, the signing key ID changes, the old file stays.
        TestKeys.write(dir, "key-new");
        AccessTokenService afterRotation = serviceSigningWith("key-new");
        String newToken = afterRotation.issue(user(Role.CUSTOMER)).value();

        JWKSet published = published("key-new");
        assertThat(published.getKeys()).hasSize(2);
        assertThat(SignedJWT.parse(newToken).getHeader().getKeyID()).isEqualTo("key-new");
        assertThat(signatureIsValid(newToken, published)).isTrue();
        assertThat(SignedJWT.parse(oldToken).getHeader().getKeyID()).isEqualTo("key-old");
        assertThat(signatureIsValid(oldToken, published)).isTrue();
    }

    @Test
    void afterTheOldKeyIsRemovedItsTokensStopWorking() throws Exception {
        TestKeys.write(dir, "key-old");
        String oldToken = serviceSigningWith("key-old").issue(user(Role.CUSTOMER)).value();
        TestKeys.write(dir, "key-new");
        java.nio.file.Files.delete(dir.resolve("key-old.pem"));

        JWKSet published = published("key-new");

        assertThat(published.getKeys()).hasSize(1);
        assertThat(signatureIsValid(oldToken, published)).isFalse();
    }

    @Test
    void theTokenStringIsNotPrintedByToString() throws Exception {
        TestKeys.write(dir, "key-1");

        IssuedToken issued = serviceSigningWith("key-1").issue(user(Role.CUSTOMER));

        assertThat(issued.toString()).doesNotContain(issued.value());
        assertThat(Map.of("t", issued.toString()).toString()).doesNotContain(issued.value());
    }
}
