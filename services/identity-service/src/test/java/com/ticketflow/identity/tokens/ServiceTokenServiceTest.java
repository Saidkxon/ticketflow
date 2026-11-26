package com.ticketflow.identity.tokens;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * ADR-0004, Part D: service tokens carry the caller, the target and the scopes, and only to a service that proves who
 * it is and is allowed to ask. Tokens are checked here with the Nimbus library directly, independent of our own code.
 */
class ServiceTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-26T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String SECRET = "a-long-random-secret-for-the-test";

    @TempDir
    Path dir;

    private SigningKeys keys;
    private ServiceTokenService service;

    @BeforeEach
    void setUp() throws Exception {
        TestKeys.write(dir, "key-1");
        keys = SigningKeys.load(dir.toString(), "key-1");
        JwtProperties jwt = new JwtProperties("test-issuer", "test-audience", Duration.ofMinutes(15), dir.toString(), "key-1");
        ServiceClientProperties clients = new ServiceClientProperties(Duration.ofMinutes(5), Map.of(
                "booking-service", new ServiceClientProperties.Client(SECRET,
                        Map.of("payment-service", Set.of("payment.attempt.create", "payment.attempt.read"))),
                "switched-off-service", new ServiceClientProperties.Client("",
                        Map.of("payment-service", Set.of("payment.attempt.create")))));
        service = new ServiceTokenService(new NimbusJwtEncoder(keys.encoderSource()), keys, jwt, clients, CLOCK);
    }

    private JWTClaimsSet claimsOf(IssuedToken token) throws Exception {
        return SignedJWT.parse(token.value()).getJWTClaimsSet();
    }

    @Test
    void theTokenNamesTheCallerTheTargetAndTheScopes() throws Exception {
        IssuedToken issued = service.issue("booking-service", SECRET, "payment-service",
                Set.of("payment.attempt.read", "payment.attempt.create"));

        JWTClaimsSet claims = claimsOf(issued);
        assertThat(claims.getIssuer()).isEqualTo("test-issuer");
        assertThat(claims.getAudience()).containsExactly("payment-service");
        assertThat(claims.getSubject()).isEqualTo("booking-service");
        assertThat(claims.getStringClaim("scope")).isEqualTo("payment.attempt.create payment.attempt.read");
        assertThat(claims.getIssueTime().toInstant()).isEqualTo(NOW);
        assertThat(claims.getExpirationTime().toInstant()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
        assertThat(claims.getJWTID()).isNotBlank();
        assertThat(issued.expiresInSeconds()).isEqualTo(300);
    }

    @Test
    void theTokenHasNoRolesSoItIsNeverTakenForAUsersToken() throws Exception {
        JWTClaimsSet claims = claimsOf(service.issue("booking-service", SECRET, "payment-service",
                Set.of("payment.attempt.create")));

        assertThat(claims.getClaims().keySet())
                .containsExactlyInAnyOrder("iss", "aud", "sub", "scope", "iat", "exp", "jti");
        assertThat(claims.getClaim("roles")).isNull();
    }

    @Test
    void theTokenIsSignedWithRs256AndTheCurrentKeyAndChecksAgainstThePublishedKeys() throws Exception {
        String token = service.issue("booking-service", SECRET, "payment-service",
                Set.of("payment.attempt.create")).value();

        SignedJWT jwt = SignedJWT.parse(token);
        JWKSet published = JWKSet.parse(keys.publicJwks());
        JWK key = published.getKeyByKeyId(jwt.getHeader().getKeyID());
        assertThat(jwt.getHeader().getAlgorithm().getName()).isEqualTo("RS256");
        assertThat(jwt.getHeader().getKeyID()).isEqualTo("key-1");
        assertThat(jwt.verify(new RSASSAVerifier(key.toRSAKey()))).isTrue();
    }

    @Test
    void aUsersAccessTokenCheckerRefusesAServiceToken() throws Exception {
        String token = service.issue("booking-service", SECRET, "payment-service",
                Set.of("payment.attempt.create")).value();
        AccessTokenVerifier usersVerifier = new AccessTokenVerifier(keys.verificationSource(), "test-issuer", "test-audience");

        assertThatThrownBy(() -> usersVerifier.verify(token)).isInstanceOf(InvalidAccessTokenException.class);
    }

    @Test
    void aWrongSecretAnAnUnknownServiceAndASwitchedOffServiceAreRefusedTheSameWay() {
        Set<String> scopes = Set.of("payment.attempt.create");

        assertThatThrownBy(() -> service.issue("booking-service", "wrong", "payment-service", scopes))
                .isExactlyInstanceOf(InvalidClientException.class);
        assertThatThrownBy(() -> service.issue("nobody-service", SECRET, "payment-service", scopes))
                .isExactlyInstanceOf(InvalidClientException.class);
        // A service whose secret was never set cannot get a token, not even with an empty secret.
        assertThatThrownBy(() -> service.issue("switched-off-service", "", "payment-service", scopes))
                .isExactlyInstanceOf(InvalidClientException.class);
        assertThatThrownBy(() -> service.issue("switched-off-service", "anything", "payment-service", scopes))
                .isExactlyInstanceOf(InvalidClientException.class);
    }

    @Test
    void aTargetOrAScopeThatIsNotAllowedIsRefused() {
        assertThatThrownBy(() -> service.issue("booking-service", SECRET, "notification-service",
                Set.of("payment.attempt.create"))).isExactlyInstanceOf(ScopeNotAllowedException.class);
        assertThatThrownBy(() -> service.issue("booking-service", SECRET, "payment-service",
                Set.of("payment.refund.create"))).isExactlyInstanceOf(ScopeNotAllowedException.class);
        // One scope too many spoils the whole request: nothing is silently left out.
        assertThatThrownBy(() -> service.issue("booking-service", SECRET, "payment-service",
                Set.of("payment.attempt.create", "payment.refund.create")))
                .isExactlyInstanceOf(ScopeNotAllowedException.class);
    }

    @Test
    void everyTokenHasItsOwnId() throws Exception {
        Set<String> scopes = Set.of("payment.attempt.create");

        String first = claimsOf(service.issue("booking-service", SECRET, "payment-service", scopes)).getJWTID();
        String second = claimsOf(service.issue("booking-service", SECRET, "payment-service", scopes)).getJWTID();

        assertThat(first).isNotEqualTo(second);
    }
}
