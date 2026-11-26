package com.ticketflow.payment.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Makes throwaway keys and tokens shaped like the ones Identity issues: service tokens (ADR-0004, Part D) and, to prove
 * they are refused here, user access tokens. Tests only.
 */
public final class TokenFixtures {

    public static final String ISSUER = "ticketflow-identity";
    /** The audience of service tokens meant for Payment. */
    public static final String AUDIENCE = "payment-service";
    /** The audience of the tokens of users (the public API). */
    public static final String USER_AUDIENCE = "ticketflow-api";

    private TokenFixtures() {
    }

    public static RSAKey newKey(String id) {
        try {
            return new RSAKeyGenerator(2048).keyID(id).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The public key set that Identity would publish for these keys, as JSON. */
    public static String jwks(RSAKey... keys) {
        JWKSet set = new JWKSet(List.of(keys)).toPublicJWKSet();
        return set.toString();
    }

    /** Claims of a good service token: from {@code caller}, for Payment, valid for ten minutes, with these scopes. */
    public static JWTClaimsSet.Builder goodClaims(String caller, String... scopes) {
        Date now = new Date();
        return new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .subject(caller)
                .issueTime(now)
                .expirationTime(new Date(now.getTime() + 600_000))
                .jwtID(UUID.randomUUID().toString())
                .claim("scope", String.join(" ", scopes));
    }

    public static String sign(RSAKey key, JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String serviceToken(RSAKey key, String caller, String... scopes) {
        return sign(key, goodClaims(caller, scopes).build());
    }

    /** A token of a user, as Identity issues it: audience of the public API, roles, no scope. */
    public static String userToken(RSAKey key, UUID user, String... roles) {
        Date now = new Date();
        return sign(key, new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(USER_AUDIENCE)
                .subject(user.toString())
                .issueTime(now)
                .expirationTime(new Date(now.getTime() + 600_000))
                .jwtID(UUID.randomUUID().toString())
                .claim("roles", List.of(roles))
                .build());
    }
}
