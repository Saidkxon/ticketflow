package com.ticketflow.identity.tokens;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The keys that sign access tokens (ADR-0004). Every file "NAME.pem" in the key directory is one
 * key whose ID is NAME. One of them, named by the signing key ID, signs new tokens. The others stay
 * published so tokens they signed still validate until those tokens expire: that is how a key is
 * rotated without logging everyone out. Private keys never leave this class; only
 * {@link #publicJwks()} is shown to the outside.
 */
public final class SigningKeys {

    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final String EXTENSION = ".pem";

    private final Map<String, RSAKey> keys;
    private final String signingKeyId;
    private final Map<String, Object> publicJwks;

    private SigningKeys(Map<String, RSAKey> keys, String signingKeyId) {
        this.keys = keys;
        this.signingKeyId = signingKeyId;
        this.publicJwks = new JWKSet(new ArrayList<JWK>(keys.values())).toPublicJWKSet().toJSONObject();
    }

    /** Fails at startup with a clear message when the configuration or a key file is wrong. */
    public static SigningKeys load(String directory, String signingKeyId) {
        if (directory == null || directory.isBlank()) {
            throw new IllegalStateException("The signing key directory is not set (ticketflow.identity.jwt.key-directory). "
                    + "For local development run: bash scripts/generate-dev-key.sh");
        }
        if (signingKeyId == null || signingKeyId.isBlank()) {
            throw new IllegalStateException("The signing key ID is not set (ticketflow.identity.jwt.signing-key-id).");
        }
        Path dir = Path.of(directory);
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("The signing key directory does not exist: " + dir
                    + ". For local development run: bash scripts/generate-dev-key.sh");
        }

        Map<String, RSAKey> keys = new TreeMap<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.sorted().toList()) {
                String name = file.getFileName().toString();
                if (!name.endsWith(EXTENSION) || !Files.isRegularFile(file)) {
                    continue;
                }
                String id = name.substring(0, name.length() - EXTENSION.length());
                if (!KEY_ID.matcher(id).matches()) {
                    throw new IllegalStateException("The key file name " + name
                            + " is not a valid key ID (letters, digits, dot, dash, underscore; at most 64)");
                }
                KeyPair pair = PemKeyReader.read(file);
                keys.put(id, new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                        .privateKey((RSAPrivateKey) pair.getPrivate())
                        .keyUse(KeyUse.SIGNATURE)
                        .algorithm(JWSAlgorithm.RS256)
                        .keyID(id)
                        .build());
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read the signing key directory " + dir, e);
        }

        if (keys.isEmpty()) {
            throw new IllegalStateException("No signing key (*.pem) found in " + dir
                    + ". For local development run: bash scripts/generate-dev-key.sh");
        }
        if (!keys.containsKey(signingKeyId)) {
            throw new IllegalStateException("The signing key ID \"" + signingKeyId + "\" has no file in " + dir
                    + ". Keys found: " + keys.keySet());
        }
        return new SigningKeys(keys, signingKeyId);
    }

    public String signingKeyId() {
        return signingKeyId;
    }

    public Set<String> keyIds() {
        return keys.keySet();
    }

    /** All keys, with their private parts, for the token encoder. Never expose this outside the service. */
    JWKSource<SecurityContext> encoderSource() {
        return new ImmutableJWKSet<>(new JWKSet(List.<JWK>copyOf(keys.values())));
    }

    /** The public half of every key, as a JSON Web Key Set (RFC 7517). */
    public Map<String, Object> publicJwks() {
        return publicJwks;
    }
}
