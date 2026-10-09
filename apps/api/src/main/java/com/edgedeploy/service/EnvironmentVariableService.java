package com.edgedeploy.service;

import com.edgedeploy.contracts.crypto.SecretContexts;
import com.edgedeploy.dto.EnvironmentVariableResponse;
import com.edgedeploy.entity.EnvironmentVariable;
import com.edgedeploy.entity.Project;
import com.edgedeploy.exception.ConflictException;
import com.edgedeploy.exception.InvalidRequestException;
import com.edgedeploy.exception.ResourceNotFoundException;
import com.edgedeploy.repository.EnvironmentVariableRepository;
import com.edgedeploy.security.SecretCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A project's runtime environment variables. Values are encrypted (AES-256-GCM, bound to project and key)
 * before they reach the database, are never returned by the api and never logged. The worker decrypts them
 * only when deploying. Changes apply to the next deployment.
 */
@Service
public class EnvironmentVariableService {

    static final Pattern KEY = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,127}$");
    static final int MAX_VARIABLES = 100;
    private static final Logger log = LoggerFactory.getLogger(EnvironmentVariableService.class);

    private final ProjectService projects;
    private final EnvironmentVariableRepository variables;
    private final SecretCipher cipher;

    public EnvironmentVariableService(ProjectService projects, EnvironmentVariableRepository variables, SecretCipher cipher) {
        this.projects = projects;
        this.variables = variables;
        this.cipher = cipher;
    }

    @Transactional(readOnly = true)
    public List<EnvironmentVariableResponse> list(UUID userId, UUID projectId) {
        projects.getOwned(userId, projectId);
        return variables.findAllByProject_IdOrderByKeyAsc(projectId).stream().map(EnvironmentVariableService::toResponse).toList();
    }

    /** Creates or replaces a variable. */
    @Transactional
    public EnvironmentVariableResponse set(UUID userId, UUID projectId, String key, String value) {
        validateKey(key);
        Project project = projects.getOwned(userId, projectId);
        String encrypted = cipher.encrypt(value, SecretContexts.environmentVariable(projectId, key));
        EnvironmentVariable variable = variables.findByProject_IdAndKey(projectId, key)
                .map(existing -> {
                    existing.replaceEncryptedValue(encrypted);
                    return existing;
                })
                .orElseGet(() -> {
                    if (variables.countByProject_Id(projectId) >= MAX_VARIABLES) {
                        throw new ConflictException("A project can have at most " + MAX_VARIABLES + " environment variables");
                    }
                    return new EnvironmentVariable(project, key, encrypted);
                });
        variables.saveAndFlush(variable);
        log.info("Set environment variable {} on project {}", key, projectId); // the name only, never the value
        return toResponse(variable);
    }

    @Transactional
    public void delete(UUID userId, UUID projectId, String key) {
        projects.getOwned(userId, projectId);
        EnvironmentVariable variable = variables.findByProject_IdAndKey(projectId, key)
                .orElseThrow(() -> new ResourceNotFoundException("Environment variable", key));
        variables.delete(variable);
        log.info("Deleted environment variable {} from project {}", key, projectId);
    }

    /** POSIX-style names; PORT and EDGEDEPLOY_* are set by the platform itself. */
    static void validateKey(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new InvalidRequestException("key", "must start with a letter or underscore and contain only letters, digits and underscores");
        }
        if (key.equals("PORT") || key.startsWith("EDGEDEPLOY_")) {
            throw new InvalidRequestException("key", key + " is reserved by EdgeDeploy");
        }
    }

    private static EnvironmentVariableResponse toResponse(EnvironmentVariable variable) {
        return new EnvironmentVariableResponse(variable.getKey(), variable.getCreatedAt(), variable.getUpdatedAt());
    }
}
