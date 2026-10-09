package com.edgedeploy.repository;

import com.edgedeploy.entity.EnvironmentVariable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EnvironmentVariableRepository extends JpaRepository<EnvironmentVariable, UUID> {

    List<EnvironmentVariable> findByProjectIdOrderByKeyAsc(UUID projectId);

    Optional<EnvironmentVariable> findByProjectIdAndKey(UUID projectId, String key);

    Optional<EnvironmentVariable> findByIdAndProjectUserId(UUID id, UUID userId);
}
