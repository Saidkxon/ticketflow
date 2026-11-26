package com.ticketflow.identity.tokens;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Issues the short-lived service token one service uses to call another (ADR-0004, Part D, client-credentials style).
 * Claims: sub (the calling service), aud (the target service), scope (space separated), iss, iat, exp and jti. There is no
 * {@code roles} claim: that is what tells every service that this is not a user's token, and it is signed with the
 * same keys as user tokens, so the same published keys check it.
 *
 * <p>A caller gets a token only for a target and scopes that the configuration lists for it. An unknown service and a
 * wrong secret give the same {@link InvalidClientException}, and the secrets are compared in constant time.
 */
@Service
public class ServiceTokenService {

    /** What the secret of an unknown service is compared with, so that an unknown name takes as long as a wrong secret. */
    private static final byte[] NO_SECRET = digest("no service has this secret");

    private final JwtEncoder encoder;
    private final SigningKeys keys;
    private final JwtProperties jwt;
    private final ServiceClientProperties clients;
    private final Clock clock;

    public ServiceTokenService(JwtEncoder encoder, SigningKeys keys, JwtProperties jwt,
            ServiceClientProperties clients, Clock clock) {
        this.encoder = encoder;
        this.keys = keys;
        this.jwt = jwt;
        this.clients = clients;
        this.clock = clock;
    }

    public IssuedToken issue(String clientId, String secret, String audience, Set<String> scopes) {
        ServiceClientProperties.Client client = clients.clients().get(clientId);
        boolean configured = client != null && client.secret() != null && !client.secret().isEmpty();
        byte[] expected = configured ? digest(client.secret()) : NO_SECRET;
        boolean secretMatches = MessageDigest.isEqual(digest(secret), expected);
        if (!configured || !secretMatches) {
            throw new InvalidClientException();
        }
        Set<String> allowed = client.allowed().get(audience);
        if (allowed == null || !allowed.containsAll(scopes)) {
            throw new ScopeNotAllowedException();
        }

        Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwt.issuer())
                .audience(List.of(audience))
                .subject(clientId)
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plus(clients.tokenTtl()))
                .id(UUID.randomUUID().toString())
                .claim("scope", String.join(" ", new TreeSet<>(scopes)))
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(keys.signingKeyId()).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, clients.tokenTtl().toSeconds());
    }

    private static byte[] digest(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
