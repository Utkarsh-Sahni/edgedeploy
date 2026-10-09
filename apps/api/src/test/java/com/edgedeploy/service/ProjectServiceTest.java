package com.edgedeploy.service;

import com.edgedeploy.dto.CreateProjectRequest;
import com.edgedeploy.dto.ProjectResponse;
import com.edgedeploy.dto.UpdateProjectRequest;
import com.edgedeploy.entity.Framework;
import com.edgedeploy.entity.Project;
import com.edgedeploy.entity.User;
import com.edgedeploy.exception.ConflictException;
import com.edgedeploy.exception.InvalidRequestException;
import com.edgedeploy.exception.ResourceNotFoundException;
import com.edgedeploy.github.GitHubApiException;
import com.edgedeploy.github.GitHubService;
import com.edgedeploy.repository.ProjectRepository;
import com.edgedeploy.repository.UserRepository;
import com.edgedeploy.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();

    @Mock
    ProjectRepository projects;
    @Mock
    UserRepository users;
    @Mock
    GitHubService gitHub;
    @Mock
    PlatformTransactionManager transactionManager;

    private ProjectService service;

    @BeforeEach
    void setUp() {
        service = new ProjectService(projects, users, gitHub, transactionManager);
        lenient().when(projects.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(users.getReferenceById(USER_ID)).thenReturn(new User("1", "octocat", "Octo", null, null));
    }

    @Test
    void createsProjectFromVerifiedGitHubRepository() {
        when(gitHub.getRepository(USER_ID, "octocat", "portfolio")).thenReturn(TestFixtures.repository("octocat/Portfolio", true));
        when(gitHub.getBranch(USER_ID, "octocat", "portfolio", "main")).thenReturn(TestFixtures.branch("main"));

        ProjectResponse project = service.create(USER_ID, request("octocat/portfolio", "main"));

        assertThat(project.repository()).isEqualTo("octocat/Portfolio"); // GitHub's canonical spelling
        assertThat(project.owner()).isEqualTo("octocat");
        assertThat(project.githubRepositoryId()).isEqualTo(4242L);
        assertThat(project.branch()).isEqualTo("main");
        assertThat(project.framework()).isEqualTo(Framework.UNKNOWN);
        assertThat(project.slug()).isEqualTo("my-portfolio");
        assertThat(project.defaultBuildCommand()).isEqualTo("npm run build");
        assertThat(project.defaultStartCommand()).isNull(); // blank -> null
    }

    @Test
    void rejectsRepositoryWithoutWriteAccess() {
        when(gitHub.getRepository(USER_ID, "torvalds", "linux")).thenReturn(TestFixtures.repository("torvalds/linux", false));

        assertThatThrownBy(() -> service.create(USER_ID, request("torvalds/linux", "master")))
                .isInstanceOfSatisfying(InvalidRequestException.class, e -> {
                    assertThat(e.field()).isEqualTo("repository");
                    assertThat(e.getMessage()).contains("write access");
                });
        verify(projects, never()).saveAndFlush(any());
    }

    @Test
    void rejectsRepositoryInvisibleToTheUser() {
        when(gitHub.getRepository(any(), anyString(), anyString()))
                .thenThrow(new GitHubApiException(GitHubApiException.Kind.NOT_FOUND, 404, "nope"));

        assertThatThrownBy(() -> service.create(USER_ID, request("someone/private-repo", "main")))
                .isInstanceOfSatisfying(InvalidRequestException.class, e -> assertThat(e.field()).isEqualTo("repository"));
    }

    @Test
    void rejectsBranchThatDoesNotExist() {
        when(gitHub.getRepository(USER_ID, "octocat", "portfolio")).thenReturn(TestFixtures.repository("octocat/portfolio", true));
        when(gitHub.getBranch(USER_ID, "octocat", "portfolio", "nope"))
                .thenThrow(new GitHubApiException(GitHubApiException.Kind.NOT_FOUND, 404, "nope"));

        assertThatThrownBy(() -> service.create(USER_ID, request("octocat/portfolio", "nope")))
                .isInstanceOfSatisfying(InvalidRequestException.class, e -> assertThat(e.field()).isEqualTo("branch"));
    }

    @Test
    void rejectsDuplicateRepository() {
        when(gitHub.getRepository(USER_ID, "octocat", "portfolio")).thenReturn(TestFixtures.repository("octocat/portfolio", true));
        when(gitHub.getBranch(USER_ID, "octocat", "portfolio", "main")).thenReturn(TestFixtures.branch("main"));
        when(projects.existsByUser_IdAndRepository(USER_ID, "octocat/portfolio")).thenReturn(true);

        assertThatThrownBy(() -> service.create(USER_ID, request("octocat/portfolio", "main")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void anotherUsersProjectIsNotFound() {
        UUID projectId = UUID.randomUUID();
        when(projects.findByIdAndUser_Id(projectId, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(USER_ID, projectId)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.delete(USER_ID, projectId)).isInstanceOf(ResourceNotFoundException.class);
        verify(projects, never()).delete(any());
    }

    @Test
    void updateVerifiesANewBranchAndKeepsTheSlug() {
        Project project = existingProject();
        when(projects.findByIdAndUser_Id(project.getId(), USER_ID)).thenReturn(Optional.of(project));
        when(gitHub.getBranch(USER_ID, "octocat", "portfolio", "develop")).thenReturn(TestFixtures.branch("develop"));

        ProjectResponse updated = service.update(USER_ID, project.getId(),
                new UpdateProjectRequest("Renamed", "develop", Framework.NEXTJS, "", null));

        assertThat(updated.name()).isEqualTo("Renamed");
        assertThat(updated.slug()).isEqualTo("portfolio");
        assertThat(updated.branch()).isEqualTo("develop");
        assertThat(updated.framework()).isEqualTo(Framework.NEXTJS);
        assertThat(updated.defaultBuildCommand()).isNull();          // "" clears
        assertThat(updated.defaultStartCommand()).isEqualTo("npm start"); // null keeps
    }

    @Test
    void updateRejectsUnknownBranchWithoutWriting() {
        Project project = existingProject();
        when(projects.findByIdAndUser_Id(project.getId(), USER_ID)).thenReturn(Optional.of(project));
        when(gitHub.getBranch(eq(USER_ID), eq("octocat"), eq("portfolio"), eq("ghost")))
                .thenThrow(new GitHubApiException(GitHubApiException.Kind.NOT_FOUND, 404, "nope"));

        assertThatThrownBy(() -> service.update(USER_ID, project.getId(),
                new UpdateProjectRequest(null, "ghost", null, null, null)))
                .isInstanceOf(InvalidRequestException.class);
        assertThat(project.getBranch()).isEqualTo("main");
    }

    @Test
    void deletesOwnProject() {
        Project project = existingProject();
        when(projects.findByIdAndUser_Id(project.getId(), USER_ID)).thenReturn(Optional.of(project));

        service.delete(USER_ID, project.getId());

        verify(projects).delete(project);
    }

    private static Project existingProject() {
        return new Project(new User("1", "octocat", "Octo", null, null), "Portfolio", "portfolio", "octocat/portfolio",
                "octocat", 4242L, "main", Framework.UNKNOWN, "npm run build", "npm start");
    }

    private static CreateProjectRequest request(String repository, String branch) {
        return new CreateProjectRequest("My Portfolio", repository, branch, null, "npm run build", "  ");
    }
}
