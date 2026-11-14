package com.ticketflow.identity.tokens;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/** Makes throwaway RSA signing keys in the PKCS#8 PEM format that the service reads. Tests only. */
public final class TestKeys {

    private TestKeys() {
    }

    /** Writes DIRECTORY/ID.pem with a new 2048-bit key. */
    public static void write(Path directory, String id) throws IOException {
        write(directory, id, 2048);
    }

    public static void write(Path directory, String id, int bits) throws IOException {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(bits);
            byte[] der = generator.generateKeyPair().getPrivate().getEncoded();
            String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
            Files.writeString(directory.resolve(id + ".pem"),
                    "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
