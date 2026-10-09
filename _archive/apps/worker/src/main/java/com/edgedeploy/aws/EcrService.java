package com.edgedeploy.aws;

import com.edgedeploy.config.WorkerProperties;
import com.edgedeploy.docker.DockerBuildService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.ecr.model.AuthorizationData;
import software.amazon.awssdk.services.ecr.model.CreateRepositoryRequest;
import software.amazon.awssdk.services.ecr.model.DescribeRepositoriesRequest;
import software.amazon.awssdk.services.ecr.model.GetAuthorizationTokenRequest;
import software.amazon.awssdk.services.ecr.model.ImageScanningConfiguration;
import software.amazon.awssdk.services.ecr.model.RepositoryAlreadyExistsException;
import software.amazon.awssdk.services.ecr.model.RepositoryNotFoundException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.function.Consumer;

@Service
@ConditionalOnProperty(prefix = "edgedeploy.aws", name = "enabled", havingValue = "true")
public class EcrService {

    private final EcrClient ecrClient;
    private final WorkerProperties properties;
    private final DockerBuildService dockerBuildService;

    public EcrService(WorkerProperties properties, DockerBuildService dockerBuildService) {
        this.properties = properties;
        this.dockerBuildService = dockerBuildService;
        this.ecrClient = EcrClient.builder()
                .region(Region.of(properties.getAws().getRegion()))
                .build();
    }

    public String ensureRepository(UUID projectId) {
        String name = "edgedeploy/" + projectId;
        try {
            ecrClient.describeRepositories(DescribeRepositoriesRequest.builder().repositoryNames(name).build());
        } catch (RepositoryNotFoundException ex) {
            try {
                ecrClient.createRepository(CreateRepositoryRequest.builder()
                        .repositoryName(name)
                        .imageScanningConfiguration(ImageScanningConfiguration.builder().scanOnPush(true).build())
                        .build());
            } catch (RepositoryAlreadyExistsException ignored) {
                // created concurrently
            }
        }
        return registry() + "/" + name;
    }

    public String imageUri(UUID projectId, String commitSha) {
        return ensureRepository(projectId) + ":" + commitSha.substring(0, Math.min(12, commitSha.length()));
    }

    public void authenticateAndPush(String imageUri, Consumer<String> logSink) throws Exception {
        AuthorizationData auth = ecrClient.getAuthorizationToken(GetAuthorizationTokenRequest.builder().build())
                .authorizationData()
                .getFirst();
        String decoded = new String(Base64.getDecoder().decode(auth.authorizationToken()), StandardCharsets.UTF_8);
        String[] parts = decoded.split(":", 2);
        dockerBuildService.login(registry(), parts[0], parts[1]);
        dockerBuildService.push(imageUri, logSink);
    }

    private String registry() {
        return properties.getAws().getAccountId() + ".dkr.ecr." + properties.getAws().getRegion() + ".amazonaws.com";
    }
}
