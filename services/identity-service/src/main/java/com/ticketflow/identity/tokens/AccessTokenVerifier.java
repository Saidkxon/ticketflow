package com.ticketflow.identity.tokens;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import com.ticketflow.identity.users.Role;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Checks an access token (ADR-0004, Part B): the signature against the published keys, only with
 * RS256 (so an unsigned token, or a token signed with a shared secret, is refused), the expiry,
 * the issuer and the audience, and that the claims the service needs are present. Any failure is
 * the same {@link InvalidAccessTokenException}, with no reason given to the caller.
 *
 * <p>Every service has its own copy of this class (ADR-0001, rule R2); only the source of the
 * keys differs: Identity reads its own key files, other services read Identity's /.well-known/jwks.json.
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
            List<String> names = claims.getStringListClaim("roles");
            Set<Role> roles = EnumSet.noneOf(Role.class);
            for (String name : names) {
                // A role this service does not know is ignored, not trusted.
                try {
                    roles.add(Role.valueOf(name));
                } catch (IllegalArgumentException unknown) {
                    // ignored on purpose
                }
            }
            return new VerifiedToken(userId, roles);
        } catch (Exception e) {
            throw new InvalidAccessTokenException(e);
        }
    }
}
