package com.edgedeploy.service;

import com.edgedeploy.contracts.crypto.SecretContexts;
import com.edgedeploy.dto.EnvironmentVariableResponse;
import com.edgedeploy.entity.EnvironmentVariable;
import com.edgedeploy.entity.Framework;
import com.edgedeploy.entity.Project;
import com.edgedeploy.entity.User;
import com.edgedeploy.exception.ConflictException;
import com.edgedeploy.exception.InvalidRequestException;
import com.edgedeploy.exception.ResourceNotFoundException;
import com.edgedeploy.repository.EnvironmentVariableRepository;
import com.edgedeploy.security.SecretCipher;
import com.edgedeploy.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EnvironmentVariableServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();

    @Mock
    ProjectService projects;
    @Mock
    EnvironmentVariableRepository variables;

    private final SecretCipher cipher = new SecretCipher(TestFixtures.properties("https://api.github.com"));
    private EnvironmentVariableService service;
    private Project project;

    @BeforeEach
    void setUp() {
        service = new EnvironmentVariableService(projects, variables, cipher);
        project = new Project(new User("1", "octocat", "Octo", null, null), "App", "app", "octocat/app", "octocat", 1L,
                "main", Framework.NODE, null, null);
        lenient().when(projects.getOwned(USER_ID, project.getId())).thenReturn(project);
        lenient().when(variables.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void storesOnlyCiphertextBoundToProjectAndKey() {
        when(variables.findByProject_IdAndKey(project.getId(), "API_URL")).thenReturn(Optional.empty());

        EnvironmentVariableResponse response = service.set(USER_ID, project.getId(), "API_URL", "https://api.example.com");

        ArgumentCaptor<EnvironmentVariable> saved = ArgumentCaptor.forClass(EnvironmentVariable.class);
        verify(variables).saveAndFlush(saved.capture());
        String stored = saved.getValue().getEncryptedValue();
        assertThat(stored).startsWith("v1:").doesNotContain("api.example.com");
        assertThat(cipher.decrypt(stored, SecretContexts.environmentVariable(project.getId(), "API_URL"))).isEqualTo("https://api.example.com");
        assertThatThrownBy(() -> cipher.decrypt(stored, SecretContexts.environmentVariable(project.getId(), "OTHER")))
                .isInstanceOf(IllegalStateException.class); // cannot be moved to another key
        assertThat(response.key()).isEqualTo("API_URL");
        assertThat(response.toString()).doesNotContain("api.example.com");
    }

    @Test
    void replacesAnExistingValue() {
        EnvironmentVariable existing = new EnvironmentVariable(project, "API_URL", "v1:old");
        when(variables.findByProject_IdAndKey(project.getId(), "API_URL")).thenReturn(Optional.of(existing));

        service.set(USER_ID, project.getId(), "API_URL", "https://new.example.com");

        assertThat(existing.getEncryptedValue()).isNotEqualTo("v1:old").startsWith("v1:");
    }

    @ParameterizedTest
    @ValueSource(strings = {"PORT", "EDGEDEPLOY_DEPLOYMENT_ID", "1BAD", "has-dash", "with space", ""})
    void rejectsReservedOrInvalidNames(String key) {
        assertThatThrownBy(() -> service.set(USER_ID, project.getId(), key, "x"))
                .isInstanceOf(InvalidRequestException.class);
        verify(variables, never()).saveAndFlush(any());
    }

    @Test
    void limitsTheNumberOfVariables() {
        when(variables.findByProject_IdAndKey(project.getId(), "ONE_MORE")).thenReturn(Optional.empty());
        when(variables.countByProject_Id(project.getId())).thenReturn(100L);

        assertThatThrownBy(() -> service.set(USER_ID, project.getId(), "ONE_MORE", "x")).isInstanceOf(ConflictException.class);
    }

    @Test
    void anotherUsersProjectIsNotFound() {
        UUID other = UUID.randomUUID();
        when(projects.getOwned(USER_ID, other)).thenThrow(new ResourceNotFoundException("Project", other));

        assertThatThrownBy(() -> service.list(USER_ID, other)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.set(USER_ID, other, "API_URL", "x")).isInstanceOf(ResourceNotFoundException.class);
    }
}
