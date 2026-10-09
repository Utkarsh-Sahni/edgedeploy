package com.edgedeploy.contracts.crypto;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Authenticated encryption for secrets at rest, shared by the api (which encrypts) and the worker
 * (which decrypts environment variables just before deploying). Plain JCE, no framework.
 *
 * <p>AES-256-GCM with a random 96-bit nonce per message. The {@code context} (see {@link SecretContexts})
 * is bound as associated data, so a ciphertext copied onto another row fails to decrypt. Output is
 * {@code v1:base64(nonce || ciphertext+tag)}; the version prefix leaves room for key rotation.
 */
public final class AesGcmCipher {

    private static final String VERSION = "v1:";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    /** @param base64Key base64-encoded 256-bit key ({@code openssl rand -base64 32}) */
    public AesGcmCipher(String base64Key) {
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(base64Key == null ? "" : base64Key);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("ENCRYPTION_KEY must be base64", e);
        }
        if (keyBytes.length != 32) {
            throw new IllegalStateException("ENCRYPTION_KEY must decode to 32 bytes; generate one with: openssl rand -base64 32");
        }
        this.key = new SecretKeySpec(keyBytes, "AES");
    }

    public String encrypt(String plaintext, String context) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = ByteBuffer.allocate(nonce.length + ciphertext.length).put(nonce).put(ciphertext).array();
            return VERSION + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public String decrypt(String encrypted, String context) {
        if (encrypted == null || !encrypted.startsWith(VERSION)) {
            throw new IllegalArgumentException("Unsupported ciphertext format");
        }
        byte[] data = Base64.getDecoder().decode(encrypted.substring(VERSION.length()));
        if (data.length <= NONCE_BYTES) {
            throw new IllegalArgumentException("Ciphertext too short");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, data, 0, NONCE_BYTES));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            byte[] plaintext = cipher.doFinal(data, NONCE_BYTES, data.length - NONCE_BYTES);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            // Wrong key, tampered data or mismatched context. Never include the ciphertext in the message.
            throw new IllegalStateException("Decryption failed", e);
        }
    }
}
