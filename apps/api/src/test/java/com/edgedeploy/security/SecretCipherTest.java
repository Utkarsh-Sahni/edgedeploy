package com.edgedeploy.security;

import com.edgedeploy.config.EdgeDeployProperties;
import com.edgedeploy.support.TestFixtures;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretCipherTest {

    private final SecretCipher cipher = new SecretCipher(TestFixtures.properties("https://api.github.com"));

    @Test
    void roundTripsAndNeverEmitsPlaintext() {
        String encrypted = cipher.encrypt("gho_secretToken123", "user-1");

        assertThat(encrypted).startsWith("v1:").doesNotContain("gho_secretToken123");
        assertThat(cipher.decrypt(encrypted, "user-1")).isEqualTo("gho_secretToken123");
    }

    @Test
    void usesAFreshNonceEveryTime() {
        assertThat(cipher.encrypt("same", "ctx")).isNotEqualTo(cipher.encrypt("same", "ctx"));
    }

    @Test
    void ciphertextIsBoundToItsContext() {
        String encrypted = cipher.encrypt("token", "user-1");

        assertThatThrownBy(() -> cipher.decrypt(encrypted, "user-2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Decryption failed");
    }

    @Test
    void detectsTampering() {
        String encrypted = cipher.encrypt("token", "ctx");
        char last = encrypted.charAt(encrypted.length() - 2);
        String tampered = encrypted.substring(0, encrypted.length() - 2) + (last == 'A' ? 'B' : 'A') + encrypted.charAt(encrypted.length() - 1);

        assertThatThrownBy(() -> cipher.decrypt(tampered, "ctx")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsKeysThatAreNot256Bits() {
        EdgeDeployProperties base = TestFixtures.properties("https://api.github.com");
        EdgeDeployProperties shortKey = new EdgeDeployProperties(List.of("http://x"), "http://x", base.cookie(),
                new EdgeDeployProperties.Encryption("c2hvcnQ="), base.github(), base.kafka(), base.outbox(),
                new EdgeDeployProperties.Sse(Duration.ofMinutes(1)));

        assertThatThrownBy(() -> new SecretCipher(shortKey))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }
}
