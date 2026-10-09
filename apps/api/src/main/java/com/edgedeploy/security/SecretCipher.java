package com.edgedeploy.security;

import com.edgedeploy.config.EdgeDeployProperties;
import com.edgedeploy.contracts.crypto.AesGcmCipher;
import org.springframework.stereotype.Component;

/**
 * Authenticated encryption for secrets at rest (GitHub tokens, environment variables). Delegates to the
 * shared {@link AesGcmCipher}, so the worker can decrypt what the api encrypts. Contexts come from
 * {@link com.edgedeploy.contracts.crypto.SecretContexts}.
 */
@Component
public class SecretCipher {

    private final AesGcmCipher cipher;

    public SecretCipher(EdgeDeployProperties properties) {
        try {
            this.cipher = new AesGcmCipher(properties.encryption().key());
        } catch (IllegalStateException e) {
            throw new IllegalStateException("edgedeploy.encryption.key: " + e.getMessage(), e);
        }
    }

    public String encrypt(String plaintext, String context) {
        return cipher.encrypt(plaintext, context);
    }

    public String decrypt(String encrypted, String context) {
        return cipher.decrypt(encrypted, context);
    }
}
