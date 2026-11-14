package com.ticketflow.identity.sessions;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RefreshTokensTest {

    @Test
    void tokensAreLongRandomAndUnique() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String token = RefreshTokens.newToken();
            // 32 random bytes in URL-safe Base64 without padding is 43 characters.
            assertThat(token).hasSize(43).matches("[A-Za-z0-9_-]+");
            assertThat(seen.add(token)).isTrue();
        }
    }

    @Test
    void theHashIsStableAndNotTheToken() {
        String token = RefreshTokens.newToken();

        assertThat(RefreshTokens.hash(token)).isEqualTo(RefreshTokens.hash(token))
                .hasSize(64)
                .isNotEqualTo(token);
        assertThat(RefreshTokens.hash(token)).isNotEqualTo(RefreshTokens.hash(RefreshTokens.newToken()));
    }

    @Test
    void knownSha256Value() {
        assertThat(RefreshTokens.hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
