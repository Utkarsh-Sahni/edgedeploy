package com.edgedeploy.worker.aws;

import com.edgedeploy.worker.delivery.ContainerRegistryService;
import com.edgedeploy.worker.delivery.DeliveryException;
import com.edgedeploy.worker.docker.DockerBuildException;
import com.edgedeploy.worker.docker.DockerImageClient;
import com.edgedeploy.worker.docker.DockerImageClient.RegistryCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.ecr.model.AuthorizationData;
import software.amazon.awssdk.services.ecr.model.EncryptionType;
import software.amazon.awssdk.services.ecr.model.ImageIdentifier;
import software.amazon.awssdk.services.ecr.model.ImageTagMutability;
import software.amazon.awssdk.services.ecr.model.Repository;
import software.amazon.awssdk.services.ecr.model.RepositoryAlreadyExistsException;
import software.amazon.awssdk.services.ecr.model.RepositoryNotFoundException;
import software.amazon.awssdk.services.ecr.model.Tag;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

/**
 * {@link ContainerRegistryService} for Amazon ECR.
 *
 * <p><b>Repository strategy: one repository per project</b> ({@code edgedeploy/{projectId}}). Compared with a
 * single shared repository this gives per-project lifecycle policies ("keep the last N images" is per
 * repository), clean IAM scoping and trivial cleanup when a project is deleted, at the cost of one more
 * resource per project (ECR allows 10,000 repositories per region by default). Repositories are created
 * lazily on a project's first push, with scan-on-push, AES-256 encryption and an expiry lifecycle policy.
 *
 * <p>Images are tagged with the commit SHA for humans, and deployed by <b>digest</b>, so a task definition
 * revision always refers to exactly the bytes that were tested, even if the tag is pushed again later.
 */
@Service
@ConditionalOnProperty(prefix = "edgedeploy.aws", name = "enabled", havingValue = "true")
public class AwsEcrService implements ContainerRegistryService {

    private static final Logger log = LoggerFactory.getLogger(AwsEcrService.class);

    private final EcrClient ecr;
    private final DockerImageClient docker;
    private final AwsResourceNames names;
    private final AwsProperties properties;
    private final Sleeper sleeper;

    public AwsEcrService(EcrClient ecr, DockerImageClient docker, AwsResourceNames names, AwsProperties properties, Sleeper sleeper) {
        this.ecr = ecr;
        this.docker = docker;
        this.names = names;
        this.properties = properties;
        this.sleeper = sleeper;
    }

    @Override
    public boolean remote() {
        return true;
    }

    @Override
    public String description() {
        return "Amazon ECR";
    }

    @Override
    public PublishedImage publish(PublishRequest request) throws DeliveryException {
        String repositoryName = names.ecrRepository(request.projectId());
        request.progress().accept("Ensuring ECR repository " + repositoryName);
        Repository repository = ensureRepository(repositoryName, request.projectId());

        String tag = tagOf(request.image().name().reference());
        String imageUri = repository.repositoryUri() + ":" + tag;

        request.progress().accept("Authenticating with ECR");
        RegistryCredentials credentials = credentials();
        try {
            docker.tag(request.image().name().reference(), imageUri);
        } catch (DockerBuildException e) {
            throw new DeliveryException(e.getMessage(), e);
        }

        request.progress().accept("Pushing image to ECR: " + imageUri);
        push(request, imageUri, credentials);

        String digest = digestOf(repositoryName, tag);
        request.progress().accept("Image pushed successfully (" + digest.substring(0, Math.min(19, digest.length())) + "…)");
        return new PublishedImage(imageUri, repository.repositoryUri() + "@" + digest, digest, true);
    }

    Repository ensureRepository(String repositoryName, UUID projectId) throws DeliveryException {
        try {
            return ecr.describeRepositories(r -> r.repositoryNames(repositoryName)).repositories().getFirst();
        } catch (RepositoryNotFoundException notFound) {
            return createRepository(repositoryName, projectId);
        } catch (SdkException e) {
            throw AwsErrors.translate("ecr:DescribeRepositories", e);
        }
    }

    private Repository createRepository(String repositoryName, UUID projectId) throws DeliveryException {
        try {
            Repository created = ecr.createRepository(r -> r
                    .repositoryName(repositoryName)
                    .imageTagMutability(ImageTagMutability.MUTABLE)
                    .imageScanningConfiguration(s -> s.scanOnPush(true))
                    .encryptionConfiguration(e -> e.encryptionType(EncryptionType.AES256))
                    .tags(Tag.builder().key(AwsTags.PROJECT).value(projectId.toString()).build(),
                            Tag.builder().key(AwsTags.MANAGED).value("true").build())).repository();
            ecr.putLifecyclePolicy(r -> r.repositoryName(repositoryName).lifecyclePolicyText(lifecyclePolicy()));
            log.info("Created ECR repository {}", repositoryName);
            return created;
        } catch (RepositoryAlreadyExistsException race) {
            // Another worker created it a moment ago: use it.
            return ecr.describeRepositories(r -> r.repositoryNames(repositoryName)).repositories().getFirst();
        } catch (SdkException e) {
            throw AwsErrors.translate("ecr:CreateRepository", e);
        }
    }

    /** Expires all but the newest {@code keepImages} images, keeping storage costs flat. */
    String lifecyclePolicy() {
        return """
                {"rules":[{"rulePriority":1,"description":"Keep the last %d images","selection":{"tagStatus":"any",\
                "countType":"imageCountMoreThan","countNumber":%d},"action":{"type":"expire"}}]}"""
                .formatted(properties.ecr().keepImages(), properties.ecr().keepImages());
    }

    /** A short-lived (12 h) registry password from GetAuthorizationToken; never logged. */
    RegistryCredentials credentials() throws DeliveryException {
        try {
            AuthorizationData data = ecr.getAuthorizationToken(r -> { }).authorizationData().getFirst();
            String decoded = new String(Base64.getDecoder().decode(data.authorizationToken()), StandardCharsets.UTF_8);
            int colon = decoded.indexOf(':');
            String host = data.proxyEndpoint().replaceFirst("^https?://", "").replaceAll("/+$", "");
            return new RegistryCredentials(host, decoded.substring(0, colon), decoded.substring(colon + 1));
        } catch (SdkException e) {
            throw AwsErrors.translate("ecr:GetAuthorizationToken", e);
        }
    }

    /**
     * Retries transient push failures (network hiccups) with backoff. Authentication failures, timeouts and
     * cancellation are not retried: they will not get better by trying again.
     */
    private void push(PublishRequest request, String imageUri, RegistryCredentials credentials) throws DeliveryException {
        int attempts = properties.ecr().pushAttempts();
        for (int attempt = 1; ; attempt++) {
            try {
                docker.push(imageUri, credentials, request.scratchDirectory().resolve("docker-config"), request.timeout(),
                        request.cancelled(), line -> { });
                return;
            } catch (DockerBuildException e) {
                boolean retryable = e.kind() == DockerBuildException.Kind.BUILD_FAILED;
                if (!retryable || attempt >= attempts) {
                    throw new DeliveryException(e.kind() == DockerBuildException.Kind.REGISTRY_AUTH
                            ? "ECR authentication failed while pushing; check the worker's ECR permissions" : e.getMessage(), e);
                }
                request.progress().accept("Push attempt " + attempt + " failed; retrying");
                try {
                    sleeper.sleep(Duration.ofSeconds(2L * attempt));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new DeliveryException("Push interrupted", interrupted);
                }
            }
        }
    }

    private String digestOf(String repositoryName, String tag) throws DeliveryException {
        try {
            return ecr.describeImages(r -> r.repositoryName(repositoryName).imageIds(ImageIdentifier.builder().imageTag(tag).build()))
                    .imageDetails().getFirst().imageDigest();
        } catch (SdkException e) {
            throw AwsErrors.translate("ecr:DescribeImages", e);
        }
    }

    private static String tagOf(String localReference) {
        return localReference.substring(localReference.lastIndexOf(':') + 1);
    }
}
