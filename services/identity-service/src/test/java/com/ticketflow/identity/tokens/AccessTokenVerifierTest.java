package com.ticketflow.identity.tokens;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.ticketflow.identity.users.Role;
import com.ticketflow.identity.users.User;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * ADR-0004 test obligations 1 and 8 for the token check: expired, wrong audience, wrong issuer
 * and tampered tokens are refused; so are unsigned tokens and tokens signed with another
 * algorithm; and a key rotation keeps old tokens valid until the old key is removed.
 */
class AccessTokenVerifierTest {

    private static final String ISSUER = "test-issuer";
    private static final String AUDIENCE = "test-audience";

    @TempDir
    Path dir;

    // ---- tokens made by our own service, checked by our own verifier

    private AccessTokenService serviceSigningWith(String signingKeyId, Clock clock, String issuer, String audience) {
        SigningKeys keys = SigningKeys.load(dir.toString(), signingKeyId);
        JwtProperties properties = new JwtProperties(
                issuer, audience, Duration.ofMinutes(15), dir.toString(), signingKeyId);
        return new AccessTokenService(new NimbusJwtEncoder(keys.encoderSource()), keys, properties, clock);
    }

    private AccessTokenService service(String signingKeyId) {
        return serviceSigningWith(signingKeyId, Clock.systemUTC(), ISSUER, AUDIENCE);
    }

    private AccessTokenVerifier verifierFor(String signingKeyId) {
        return new AccessTokenVerifier(SigningKeys.load(dir.toString(), signingKeyId).verificationSource(),
                ISSUER, AUDIENCE);
    }

    private static User user(Role... roles) {
        return new User(UUID.randomUUID(), "ada@example.com", "{bcrypt}hash", "Ada Lovelace", Set.of(roles));
    }

    @Test
    void aFreshTokenIsAcceptedAndGivesTheUserAndRoles() throws Exception {
        TestKeys.write(dir, "key-1");
        User user = user(Role.CUSTOMER, Role.ORGANIZER);

        VerifiedToken verified = verifierFor("key-1").verify(service("key-1").issue(user).value());

        assertThat(verified.userId()).isEqualTo(user.getId());
        assertThat(verified.roles()).containsExactlyInAnyOrder(Role.CUSTOMER, Role.ORGANIZER);
    }

    @Test
    void anExpiredTokenIsRefused() throws Exception {
        TestKeys.write(dir, "key-1");
        Clock twoHoursAgo = Clock.fixed(Instant.now().minus(Duration.ofHours(2)), ZoneOffset.UTC);
        String token = serviceSigningWith("key-1", twoHoursAgo, ISSUER, AUDIENCE).issue(user(Role.CUSTOMER)).value();

        assertRefused(verifierFor("key-1"), token);
    }

    @Test
    void aTokenForAnotherAudienceIsRefused() throws Exception {
        TestKeys.write(dir, "key-1");
        String token = serviceSigningWith("key-1", Clock.systemUTC(), ISSUER, "some-other-api")
                .issue(user(Role.CUSTOMER)).value();

        assertRefused(verifierFor("key-1"), token);
    }

    @Test
    void aTokenFromAnotherIssuerIsRefused() throws Exception {
        TestKeys.write(dir, "key-1");
        String token = serviceSigningWith("key-1", Clock.systemUTC(), "someone-else", AUDIENCE)
                .issue(user(Role.CUSTOMER)).value();

        assertRefused(verifierFor("key-1"), token);
    }

    @Test
    void aTamperedPayloadIsRefused() throws Exception {
        TestKeys.write(dir, "key-1");
        String[] parts = service("key-1").issue(user(Role.CUSTOMER)).value().split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                .replace("\"CUSTOMER\"", "\"ADMIN\"");
        String forged = parts[0] + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8))
                + "." + parts[2];

        assertThat(payload).contains("ADMIN");
        assertRefused(verifierFor("key-1"), forged);
    }

    @Test
    void aTokenSignedByAnotherKeyWithTheSameIdIsRefused() throws Exception {
        TestKeys.write(dir, "key-1");
        Path elsewhere = Files.createDirectory(dir.resolve("elsewhere"));
        TestKeys.write(elsewhere, "key-1");
        SigningKeys strangerKeys = SigningKeys.load(elsewhere.toString(), "key-1");
        JwtProperties properties = new JwtProperties(ISSUER, AUDIENCE, Duration.ofMinutes(15),
                elsewhere.toString(), "key-1");
        String token = new AccessTokenService(new NimbusJwtEncoder(strangerKeys.encoderSource()), strangerKeys,
                properties, Clock.systemUTC()).issue(user(Role.CUSTOMER)).value();

        assertRefused(verifierFor("key-1"), token);
    }

    @Test
    void aTokenWithAnUnknownKeyIdIsRefused() throws Exception {
        TestKeys.write(dir, "key-1");
        Path elsewhere = Files.createDirectory(dir.resolve("elsewhere"));
        TestKeys.write(elsewhere, "key-other");
        SigningKeys strangerKeys = SigningKeys.load(elsewhere.toString(), "key-other");
        JwtProperties properties = new JwtProperties(ISSUER, AUDIENCE, Duration.ofMinutes(15),
                elsewhere.toString(), "key-other");
        String token = new AccessTokenService(new NimbusJwtEncoder(strangerKeys.encoderSource()), strangerKeys,
                properties, Clock.systemUTC()).issue(user(Role.CUSTOMER)).value();

        assertRefused(verifierFor("key-1"), token);
    }

    @Test
    void rotatingTheKeyKeepsOldTokensValidUntilTheOldKeyIsRemoved() throws Exception {
        TestKeys.write(dir, "key-old");
        String oldToken = service("key-old").issue(user(Role.CUSTOMER)).value();
        TestKeys.write(dir, "key-new");
        String newToken = service("key-new").issue(user(Role.CUSTOMER)).value();

        AccessTokenVerifier both = verifierFor("key-new");
        assertThat(both.verify(oldToken)).isNotNull();
        assertThat(both.verify(newToken)).isNotNull();

        Files.delete(dir.resolve("key-old.pem"));
        AccessTokenVerifier onlyNew = verifierFor("key-new");
        assertRefused(onlyNew, oldToken);
        assertThat(onlyNew.verify(newToken)).isNotNull();
    }

    @Test
    void garbageAndEmptyInputAreRefused() throws Exception {
        TestKeys.write(dir, "key-1");
        AccessTokenVerifier verifier = verifierFor("key-1");

        for (String garbage : new String[] {"", "   ", "not.a.token", "a.b.c", "eyJhbGciOiJSUzI1NiJ9", "Bearer x"}) {
            assertRefused(verifier, garbage);
        }
        assertThatThrownBy(() -> verifier.verify(null)).isInstanceOf(InvalidAccessTokenException.class);
    }

    // ---- tokens made by hand, to test what our own service never produces

    private final RSAKey handKey = generate("hand-key");
    private final AccessTokenVerifier handVerifier = new AccessTokenVerifier(
            new ImmutableJWKSet<>(new JWKSet(handKey.toPublicJWK())), ISSUER, AUDIENCE);

    private static RSAKey generate(String id) {
        try {
            return new RSAKeyGenerator(2048).keyID(id).generate();
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private JWTClaimsSet.Builder goodClaims() {
        Date now = new Date();
        return new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .subject(UUID.randomUUID().toString())
                .issueTime(now)
                .expirationTime(new Date(now.getTime() + 600_000))
                .jwtID(UUID.randomUUID().toString())
                .claim("roles", List.of("CUSTOMER"));
    }

    private String sign(JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(handKey.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(handKey));
        return jwt.serialize();
    }

    @Test
    void theHandMadeGoodTokenIsAccepted() throws Exception {
        assertThat(handVerifier.verify(sign(goodClaims().build()))).isNotNull();
    }

    @Test
    void anUnsignedTokenIsRefused() {
        assertRefused(handVerifier, new PlainJWT(goodClaims().build()).serialize());
    }

    @Test
    void aTokenSignedWithASharedSecretInsteadOfRs256IsRefused() throws Exception {
        byte[] secret = new byte[32];
        java.util.Arrays.fill(secret, (byte) 7);
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(handKey.getKeyID()).build(), goodClaims().build());
        jwt.sign(new MACSigner(secret));

        assertRefused(handVerifier, jwt.serialize());
    }

    @Test
    void aTokenMissingARequiredClaimIsRefused() throws Exception {
        assertRefused(handVerifier, sign(new JWTClaimsSet.Builder()
                .issuer(ISSUER).audience(AUDIENCE).subject(UUID.randomUUID().toString())
                .issueTime(new Date()).expirationTime(new Date(System.currentTimeMillis() + 600_000))
                .claim("roles", List.of("CUSTOMER")).build()));   // no jti
        assertRefused(handVerifier, sign(new JWTClaimsSet.Builder()
                .issuer(ISSUER).audience(AUDIENCE).subject(UUID.randomUUID().toString())
                .issueTime(new Date()).jwtID("x").claim("roles", List.of("CUSTOMER")).build()));   // no exp
    }

    @Test
    void aSubjectThatIsNotAUserIdIsRefused() throws Exception {
        assertRefused(handVerifier, sign(goodClaims().subject("not-a-uuid").build()));
    }

    @Test
    void anUnknownRoleIsIgnoredNotTrusted() throws Exception {
        VerifiedToken verified = handVerifier.verify(
                sign(goodClaims().claim("roles", List.of("CUSTOMER", "SUPERUSER")).build()));

        assertThat(verified.roles()).containsExactly(Role.CUSTOMER);
    }

    private static void assertRefused(AccessTokenVerifier verifier, String token) {
        assertThatThrownBy(() -> verifier.verify(token)).isInstanceOf(InvalidAccessTokenException.class);
    }
}
