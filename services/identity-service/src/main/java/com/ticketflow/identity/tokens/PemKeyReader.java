package com.ticketflow.identity.tokens;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

/**
 * Reads an RSA private key from a PEM file in PKCS#8 format ("BEGIN PRIVATE KEY") and derives the
 * public key from it. Error messages name the file but never contain key material.
 */
final class PemKeyReader {

    static final int MIN_KEY_BITS = 2048;

    private static final String BEGIN = "-----BEGIN PRIVATE KEY-----";
    private static final String END = "-----END PRIVATE KEY-----";

    private PemKeyReader() {
    }

    static KeyPair read(Path file) {
        String text;
        try {
            text = Files.readString(file);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read the signing key file " + file.getFileName(), e);
        }
        if (!text.contains(BEGIN) || !text.contains(END)) {
            throw new IllegalStateException("The signing key file " + file.getFileName()
                    + " is not a PKCS#8 PEM private key (it must start with \"" + BEGIN + "\"). "
                    + "Convert it with: openssl pkcs8 -topk8 -nocrypt -in old.pem -out new.pem");
        }
        String base64 = text.substring(text.indexOf(BEGIN) + BEGIN.length(), text.indexOf(END))
                .replaceAll("\\s", "");
        try {
            byte[] der = Base64.getDecoder().decode(base64);
            KeyFactory factory = KeyFactory.getInstance("RSA");
            if (!(factory.generatePrivate(new PKCS8EncodedKeySpec(der)) instanceof RSAPrivateCrtKey privateKey)) {
                throw new IllegalStateException("The signing key file " + file.getFileName()
                        + " does not hold a complete RSA private key");
            }
            if (privateKey.getModulus().bitLength() < MIN_KEY_BITS) {
                throw new IllegalStateException("The signing key " + file.getFileName()
                        + " is shorter than " + MIN_KEY_BITS + " bits");
            }
            RSAPublicKey publicKey = (RSAPublicKey) factory.generatePublic(
                    new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
            return new KeyPair(publicKey, privateKey);
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            throw new IllegalStateException("The signing key file " + file.getFileName()
                    + " could not be parsed as an RSA private key", e);
        }
    }
}
