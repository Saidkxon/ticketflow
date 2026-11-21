package com.ticketflow.booking.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Checks an access token in the Booking service (ADR-0004, Part B): the signature against Identity's published
 * keys, only with RS256 (so an unsigned token, or one signed with a shared secret, is refused), the
 * expiry, the issuer and the audience, and that the claims the services need are present. Any failure is
 * the same {@link InvalidAccessTokenException}, with no reason given to the caller. If the keys cannot be
 * fetched, {@link KeysUnavailableException} says so: that is an outage, not a bad token.
 *
 * <p>This is a copy of the class in the gateway, which is a copy of the one in the Identity service (ADR-0001, rule R2), with roles kept as plain text.
 */
public final class AccessTokenVerifier {

    /** Allowed difference between clocks, in seconds. Access tokens live 15 minutes, so this stays small. */
    private static final int CLOCK_SKEW_SECONDS = 5;

    private final ConfigurableJWTProcessor<SecurityContext> processor;

    public AccessTokenVerifier(JWKSource<SecurityContext> keys, String issuer, String audience) {
        DefaultJWTProcessor<SecurityContext> jwtProcessor = new DefaultJWTProcessor<>();
        jwtProcessor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keys));
        DefaultJWTClaimsVerifier<SecurityContext> claims = new DefaultJWTClaimsVerifier<>(
                Set.of(audience),
                new JWTClaimsSet.Builder().issuer(issuer).build(),
                Set.of("sub", "exp", "iat", "jti", "roles"),
                Set.of());
        claims.setMaxClockSkew(CLOCK_SKEW_SECONDS);
        jwtProcessor.setJWTClaimsSetVerifier(claims);
        this.processor = jwtProcessor;
    }

    public VerifiedToken verify(String token) {
        if (token == null || token.isBlank()) {
            throw new InvalidAccessTokenException();
        }
        try {
            JWTClaimsSet claims = processor.process(token, null);
            UUID userId = UUID.fromString(claims.getSubject());
            List<String> roles = claims.getStringListClaim("roles");
            return new VerifiedToken(userId, new HashSet<>(roles));
        } catch (KeySourceException e) {
            throw new KeysUnavailableException(e);
        } catch (Exception e) {
            throw new InvalidAccessTokenException(e);
        }
    }
}
