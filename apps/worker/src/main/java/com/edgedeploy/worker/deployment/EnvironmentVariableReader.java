package com.edgedeploy.worker.deployment;

import com.edgedeploy.contracts.crypto.AesGcmCipher;
import com.edgedeploy.contracts.crypto.SecretContexts;
import com.edgedeploy.worker.config.WorkerProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Loads a project's environment variables, decrypted, immediately before they are handed to the deployment
 * target. Plaintext exists only in memory for that call: it is never logged, put on Kafka or written back.
 * The api encrypts with the same key and the same per-row context ({@link SecretContexts}).
 */
@Component
public class EnvironmentVariableReader {

    private final JdbcClient jdbc;
    private final String key;
    private volatile AesGcmCipher cipher;

    public EnvironmentVariableReader(JdbcClient jdbc, WorkerProperties properties) {
        this.jdbc = jdbc;
        this.key = properties.encryption().key();
    }

    /** @throws DeploymentConfigurationException if variables exist but cannot be decrypted */
    public Map<String, String> read(UUID projectId) {
        List<Map<String, Object>> rows = jdbc.sql("SELECT key, encrypted_value FROM environment_variables WHERE project_id = ? ORDER BY key")
                .param(projectId).query().listOfRows();
        if (rows.isEmpty()) {
            return Map.of();
        }
        AesGcmCipher aes = cipher();
        Map<String, String> variables = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String name = (String) row.get("key");
            try {
                variables.put(name, aes.decrypt((String) row.get("encrypted_value"), SecretContexts.environmentVariable(projectId, name)));
            } catch (RuntimeException e) {
                throw new DeploymentConfigurationException("Environment variable " + name + " could not be decrypted. "
                        + "The worker's ENCRYPTION_KEY must match the api's.");
            }
        }
        return variables;
    }

    private AesGcmCipher cipher() {
        if (key == null || key.isBlank()) {
            throw new DeploymentConfigurationException("This project has environment variables, but ENCRYPTION_KEY is not "
                    + "configured on the build worker.");
        }
        if (cipher == null) {
            cipher = new AesGcmCipher(key);
        }
        return cipher;
    }

    /** The deployment cannot proceed because of how EdgeDeploy is configured. User-facing message. */
    public static class DeploymentConfigurationException extends RuntimeException {
        public DeploymentConfigurationException(String message) {
            super(message);
        }
    }
}
