package com.edgedeploy.controller;

import com.edgedeploy.config.EdgeDeployProperties;
import com.edgedeploy.dto.DeploymentResponse;
import com.edgedeploy.exception.ApiException;
import com.edgedeploy.service.DeploymentService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@RestController
@RequestMapping("/api/v1/webhooks/github")
public class GitHubWebhookController {

    private final DeploymentService deploymentService;
    private final EdgeDeployProperties properties;
    private final ObjectMapper objectMapper;

    public GitHubWebhookController(
            DeploymentService deploymentService,
            EdgeDeployProperties properties,
            ObjectMapper objectMapper
    ) {
        this.deploymentService = deploymentService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ResponseEntity<DeploymentResponse> handle(
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestHeader(value = "X-GitHub-Event", required = false) String event,
            @RequestBody String payload
    ) throws Exception {
        verifySignature(payload, signature);
        if (!"push".equals(event)) {
            return ResponseEntity.ok().build();
        }
        JsonNode root = objectMapper.readTree(payload);
        String repository = root.path("repository").path("full_name").asText();
        String ref = root.path("ref").asText();
        String sha = root.path("after").asText();
        if (repository.isBlank() || !ref.startsWith("refs/heads/") || sha.isBlank() || sha.matches("0+")) {
            return ResponseEntity.ok().build();
        }
        String branch = ref.substring("refs/heads/".length());
        try {
            DeploymentResponse created = deploymentService.createFromWebhook(repository, branch, sha);
            if (created == null) {
                return ResponseEntity.ok().build();
            }
            return ResponseEntity.accepted().body(created);
        } catch (ApiException ex) {
            if (ex.getStatus() == HttpStatus.NOT_FOUND) {
                return ResponseEntity.ok().build();
            }
            throw ex;
        }
    }

    private void verifySignature(String payload, String signature) throws Exception {
        String secret = properties.getGithub().getWebhookSecret();
        if (secret == null || secret.isBlank()) {
            return;
        }
        if (signature == null || !signature.startsWith("sha256=")) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Missing GitHub signature");
        }
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        String expected = "sha256=" + HexFormat.of().formatHex(digest);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid GitHub signature");
        }
    }
}
