package com.ticketflow.identity.tokens;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** ADR-0004: keys come from files, are published only in their public form, and a bad setup stops the service. */
class SigningKeysTest {

    @TempDir
    Path dir;

    @Test
    void loadsEveryPemFileAndIgnoresOtherFiles() throws Exception {
        TestKeys.write(dir, "key-a");
        TestKeys.write(dir, "key-b");
        Files.writeString(dir.resolve("notes.txt"), "not a key");

        SigningKeys keys = SigningKeys.load(dir.toString(), "key-b");

        assertThat(keys.keyIds()).containsExactly("key-a", "key-b");
        assertThat(keys.signingKeyId()).isEqualTo("key-b");
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishesOnlyThePublicPartOfEveryKey() throws Exception {
        TestKeys.write(dir, "key-a");
        TestKeys.write(dir, "key-b");

        SigningKeys keys = SigningKeys.load(dir.toString(), "key-a");

        List<Map<String, Object>> published = (List<Map<String, Object>>) keys.publicJwks().get("keys");
        assertThat(published).hasSize(2);
        for (Map<String, Object> key : published) {
            assertThat(key).containsEntry("kty", "RSA").containsEntry("alg", "RS256").containsEntry("use", "sig");
            assertThat(key).containsKeys("kid", "n", "e");
            // d, p, q, dp, dq and qi are the private parts of an RSA key.
            assertThat(key).doesNotContainKeys("d", "p", "q", "dp", "dq", "qi");
        }
    }

    @Test
    void refusesToStartWithoutADirectory() {
        assertThatThrownBy(() -> SigningKeys.load("", "key-a"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("key-directory");
        assertThatThrownBy(() -> SigningKeys.load(null, "key-a"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("key-directory");
        assertThatThrownBy(() -> SigningKeys.load(dir.resolve("missing").toString(), "key-a"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("does not exist");
    }

    @Test
    void refusesToStartWithoutAKeyOrWithoutASigningKeyId() throws Exception {
        assertThatThrownBy(() -> SigningKeys.load(dir.toString(), "key-a"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("No signing key");

        TestKeys.write(dir, "key-a");

        assertThatThrownBy(() -> SigningKeys.load(dir.toString(), ""))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("signing-key-id");
        assertThatThrownBy(() -> SigningKeys.load(dir.toString(), "other"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("\"other\"").hasMessageContaining("key-a");
    }

    @Test
    void refusesAKeyThatIsTooShort() throws Exception {
        TestKeys.write(dir, "weak", 1024);

        assertThatThrownBy(() -> SigningKeys.load(dir.toString(), "weak"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("2048");
    }

    @Test
    void refusesAFileThatIsNotAPkcs8Key() throws Exception {
        Files.writeString(dir.resolve("old.pem"), "-----BEGIN RSA PRIVATE KEY-----\nAAAA\n-----END RSA PRIVATE KEY-----\n");

        assertThatThrownBy(() -> SigningKeys.load(dir.toString(), "old"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("PKCS#8");
    }

    @Test
    void errorMessagesNeverContainKeyMaterial() throws Exception {
        Files.writeString(dir.resolve("bad.pem"),
                "-----BEGIN PRIVATE KEY-----\nSECRETSECRETSECRET\n-----END PRIVATE KEY-----\n");

        assertThatThrownBy(() -> SigningKeys.load(dir.toString(), "bad"))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(e -> assertThat(String.valueOf(e.getMessage())).doesNotContain("SECRETSECRETSECRET"));
    }
}
