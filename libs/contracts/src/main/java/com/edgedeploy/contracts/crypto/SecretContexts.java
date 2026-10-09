package com.edgedeploy.contracts.crypto;

import java.util.UUID;

/** Associated-data contexts for {@link AesGcmCipher}. Both sides must build them identically. */
public final class SecretContexts {

    private SecretContexts() {
    }

    public static String githubToken(UUID userId) {
        return "github-token:" + userId;
    }

    /** Binds an environment variable's ciphertext to its project and key: it cannot be moved to another row. */
    public static String environmentVariable(UUID projectId, String key) {
        return "env-var:" + projectId + ":" + key;
    }
}
