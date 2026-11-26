package com.ticketflow.payment.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Checks a service token on an internal endpoint (ADR-0004, Part D): the signature against Identity's published keys,
 * only with RS256, the expiry, the issuer, and that the audience is this service. The caller is the subject and the
 * permissions are in the {@code scope} claim (space separated, as in OAuth 2).
 *
 * <p>A user's access token is never accepted here: it has another audience, and a token with a {@code roles} claim is
 * refused even if somebody managed to give it this audience. Any failure is the same
 * {@link InvalidAccessTokenException}, with no reason given to the caller. If the keys cannot be fetched,
 * {@link KeysUnavailableException} says so: that is an outage, not a bad token.
 *
 * <p>The signature and claim checks are the same as in the other services' {@code AccessTokenVerifier} (ADR-0001,
 * rule R2: each service has its own copy); only the claims differ.
 */
public final class ServiceTokenVerifier {

    /** Allowed difference between clocks, in seconds. Service tokens live a few minutes, so this stays small. */
    private static final int CLOCK_SKEW_SECONDS = 5;

    /** A service name: lower case letters, digits and hyphens, for example {@code booking-service}. */
    private static final Pattern SERVICE_NAME = Pattern.compile("[a-z][a-z0-9-]{0,63}");

    private final ConfigurableJWTProcessor<SecurityContext> processor;

    public ServiceTokenVerifier(JWKSource<SecurityContext> keys, String issuer, String audience) {
        DefaultJWTProcessor<SecurityContext> jwtProcessor = new DefaultJWTProcessor<>();
        jwtProcessor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keys));
        DefaultJWTClaimsVerifier<SecurityContext> claims = new DefaultJWTClaimsVerifier<>(
                Set.of(audience),
                new JWTClaimsSet.Builder().issuer(issuer).build(),
                Set.of("sub", "exp", "iat", "jti", "scope"),
                Set.of("roles"));
        claims.setMaxClockSkew(CLOCK_SKEW_SECONDS);
        jwtProcessor.setJWTClaimsSetVerifier(claims);
        this.processor = jwtProcessor;
    }

    public ServiceToken verify(String token) {
        if (token == null || token.isBlank()) {
            throw new InvalidAccessTokenException();
        }
        try {
            JWTClaimsSet claims = processor.process(token, null);
            String caller = claims.getSubject();
            if (!SERVICE_NAME.matcher(caller).matches()) {
                throw new InvalidAccessTokenException();
            }
            String scope = claims.getStringClaim("scope");
            Set<String> scopes = new LinkedHashSet<>(Arrays.asList(scope.trim().split("\\s+")));
            scopes.remove("");
            if (scopes.isEmpty()) {
                throw new InvalidAccessTokenException();
            }
            return new ServiceToken(caller, scopes);
        } catch (InvalidAccessTokenException e) {
            throw e;
        } catch (KeySourceException e) {
            throw new KeysUnavailableException(e);
        } catch (Exception e) {
            throw new InvalidAccessTokenException(e);
        }
    }
}
