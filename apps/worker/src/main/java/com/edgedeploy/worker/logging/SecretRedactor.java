package com.edgedeploy.worker.logging;

import com.edgedeploy.worker.config.WorkerProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Last line of defence before text reaches deployment logs or error messages: masks the configured
 * deploy token (raw and as the base64 Basic-auth value git sends) and anything shaped like a GitHub
 * token or a URL with embedded credentials. Tokens should never get this far; this catches mistakes
 * and secrets printed by user build scripts.
 */
@Component
public class SecretRedactor {

    static final String MASK = "[REDACTED]";
    private static final Pattern GITHUB_TOKEN = Pattern.compile("\\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})\\b");
    private static final Pattern URL_CREDENTIALS = Pattern.compile("(?i)(https?://)[^/@\\s:]+:[^/@\\s]+@");
    private static final Pattern AUTH_HEADER = Pattern.compile("(?i)(authorization:\\s*(?:basic|bearer|token)\\s+)\\S+");

    private final List<String> literals = new ArrayList<>();

    public SecretRedactor(WorkerProperties properties) {
        String token = properties.git().deployToken();
        if (token != null && !token.isBlank()) {
            literals.add(token);
            literals.add(Base64.getEncoder().encodeToString(("x-access-token:" + token).getBytes()));
        }
    }

    public String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        for (String literal : literals) {
            result = result.replace(literal, MASK);
        }
        result = GITHUB_TOKEN.matcher(result).replaceAll(MASK);
        result = URL_CREDENTIALS.matcher(result).replaceAll("$1" + MASK + "@");
        result = AUTH_HEADER.matcher(result).replaceAll("$1" + MASK);
        return result;
    }
}
