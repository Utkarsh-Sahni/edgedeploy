package com.edgedeploy.github;

import com.edgedeploy.contracts.crypto.SecretContexts;
import com.edgedeploy.entity.GitHubCredential;
import com.edgedeploy.repository.GitHubCredentialRepository;
import com.edgedeploy.security.SecretCipher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;

/** Stores GitHub tokens AES-GCM encrypted, bound to the owning user id. */
@Component
public class EncryptedGitHubTokenStore implements GitHubTokenStore {

    private final GitHubCredentialRepository credentials;
    private final SecretCipher cipher;

    public EncryptedGitHubTokenStore(GitHubCredentialRepository credentials, SecretCipher cipher) {
        this.credentials = credentials;
        this.cipher = cipher;
    }

    @Override
    @Transactional
    public void save(UUID userId, String accessToken, Collection<String> scopes) {
        String encrypted = cipher.encrypt(accessToken, context(userId));
        String scopeList = scopes == null ? null : String.join(",", new TreeSet<>(scopes));
        credentials.findById(userId).ifPresentOrElse(
                existing -> existing.replace(encrypted, scopeList),
                () -> credentials.save(new GitHubCredential(userId, encrypted, scopeList)));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> findAccessToken(UUID userId) {
        return credentials.findById(userId)
                .map(credential -> cipher.decrypt(credential.getEncryptedAccessToken(), context(userId)));
    }

    @Override
    @Transactional
    public void delete(UUID userId) {
        credentials.deleteById(userId);
    }

    private static String context(UUID userId) {
        return SecretContexts.githubToken(userId);
    }
}
