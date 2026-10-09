package com.edgedeploy.service;

import com.edgedeploy.entity.User;
import com.edgedeploy.exception.ApiException;
import com.edgedeploy.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final EncryptionService encryptionService;

    public UserService(UserRepository userRepository, EncryptionService encryptionService) {
        this.userRepository = userRepository;
        this.encryptionService = encryptionService;
    }

    @Transactional
    public User upsertFromGithub(OAuth2User principal, String accessToken) {
        String githubId = String.valueOf(principal.getAttribute("id"));
        String login = principal.getAttribute("login");
        String name = principal.getAttribute("name");
        String email = resolveEmail(principal);
        User user = userRepository.findByGithubId(githubId).orElseGet(User::new);
        user.setGithubId(githubId);
        user.setGithubLogin(login);
        user.setName(name == null || name.isBlank() ? login : name);
        user.setEmail(email);
        if (accessToken != null) {
            user.setGithubAccessToken(encryptionService.encrypt(accessToken));
        }
        return userRepository.save(user);
    }

    @Transactional(readOnly = true)
    public User getRequired(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "User not found"));
    }

    public String decryptGithubToken(User user) {
        if (user.getGithubAccessToken() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "GitHub token is missing. Sign in again.");
        }
        return encryptionService.decrypt(user.getGithubAccessToken());
    }

    @SuppressWarnings("unchecked")
    private String resolveEmail(OAuth2User principal) {
        String email = principal.getAttribute("email");
        if (email != null && !email.isBlank()) {
            return email;
        }
        Object emails = principal.getAttribute("emails");
        if (emails instanceof List<?> list && !list.isEmpty() && list.getFirst() instanceof Map<?, ?> map) {
            Object value = map.get("email");
            if (value != null) {
                return String.valueOf(value);
            }
        }
        String login = principal.getAttribute("login");
        return login + "@users.noreply.github.com";
    }
}
