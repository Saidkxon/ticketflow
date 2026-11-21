package com.ticketflow.booking.security;

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

/** Makes throwaway keys and access tokens shaped like the ones Identity issues. Tests only. */
public final class TokenFixtures {

    public static final String ISSUER = "ticketflow-identity";
    public static final String AUDIENCE = "ticketflow-api";

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

    public static JWTClaimsSet.Builder goodClaims(UUID subject) {
        Date now = new Date();
        return new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .subject(subject.toString())
                .issueTime(now)
                .expirationTime(new Date(now.getTime() + 600_000))
                .jwtID(UUID.randomUUID().toString())
                .claim("roles", List.of("CUSTOMER"));
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

    public static String token(RSAKey key, UUID subject) {
        return sign(key, goodClaims(subject).build());
    }

    /** A valid token for this user with exactly these roles. */
    public static String token(RSAKey key, UUID subject, String... roles) {
        return sign(key, goodClaims(subject).claim("roles", List.of(roles)).build());
    }
}
