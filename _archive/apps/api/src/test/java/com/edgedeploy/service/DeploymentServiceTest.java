package com.edgedeploy.service;

import com.edgedeploy.deployment.DeploymentStatus;
import com.edgedeploy.dto.CreateDeploymentRequest;
import com.edgedeploy.entity.Deployment;
import com.edgedeploy.entity.Project;
import com.edgedeploy.entity.User;
import com.edgedeploy.github.GitHubClient;
import com.edgedeploy.kafka.DeploymentEventProducer;
import com.edgedeploy.kafka.DeploymentRequestedEvent;
import com.edgedeploy.mapper.EntityMappers;
import com.edgedeploy.repository.DeploymentLogRepository;
import com.edgedeploy.repository.DeploymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeploymentServiceTest {

    @Mock
    private DeploymentRepository deploymentRepository;
    @Mock
    private DeploymentLogRepository deploymentLogRepository;
    @Mock
    private ProjectService projectService;
    @Mock
    private UserService userService;
    @Mock
    private GitHubClient gitHubClient;
    @Mock
    private DeploymentEventProducer eventProducer;
    @Mock
    private DeploymentCacheService cacheService;

    private DeploymentService deploymentService;

    @BeforeEach
    void setUp() {
        deploymentService = new DeploymentService(
                deploymentRepository,
                deploymentLogRepository,
                projectService,
                userService,
                gitHubClient,
                eventProducer,
                cacheService,
                new EntityMappers()
        );
    }

    @Test
    void createPublishesKafkaEventAndReturnsQueuedDeployment() {
        UUID userId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        User user = new User();
        user.setId(userId);
        Project project = new Project();
        project.setId(projectId);
        project.setUser(user);
        project.setRepository("acme/web");
        project.setBranch("main");

        when(projectService.requireOwned(userId, projectId)).thenReturn(project);
        when(userService.getRequired(userId)).thenReturn(user);
        when(userService.decryptGithubToken(user)).thenReturn("token");
        when(gitHubClient.resolveCommitSha("token", "acme/web", "main")).thenReturn("abc123def456");
        when(deploymentRepository.save(any(Deployment.class))).thenAnswer(invocation -> {
            Deployment deployment = invocation.getArgument(0);
            deployment.setId(UUID.randomUUID());
            return deployment;
        });

        var response = deploymentService.create(userId, projectId, new CreateDeploymentRequest(null));

        assertThat(response.status()).isEqualTo(DeploymentStatus.QUEUED);
        assertThat(response.commitSha()).isEqualTo("abc123def456");
        ArgumentCaptor<DeploymentRequestedEvent> captor = ArgumentCaptor.forClass(DeploymentRequestedEvent.class);
        verify(eventProducer).publishRequested(captor.capture());
        assertThat(captor.getValue().repository()).isEqualTo("acme/web");
        verify(cacheService).cacheStatus(any(), org.mockito.ArgumentMatchers.eq(DeploymentStatus.QUEUED));
    }
}
