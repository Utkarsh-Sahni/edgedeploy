package com.edgedeploy.aws;

import com.edgedeploy.config.WorkerProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "edgedeploy.aws", name = "enabled", havingValue = "true")
public class S3Service {

    private final S3Client s3Client;
    private final WorkerProperties properties;

    public S3Service(WorkerProperties properties) {
        this.properties = properties;
        this.s3Client = S3Client.builder()
                .region(Region.of(properties.getAws().getRegion()))
                .build();
    }

    public void uploadLogs(UUID deploymentId, String logs) {
        String key = "deployments/" + deploymentId + "/build.log";
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(properties.getAws().getLogBucket())
                        .key(key)
                        .contentType("text/plain")
                        .build(),
                RequestBody.fromString(logs, StandardCharsets.UTF_8)
        );
    }
}
