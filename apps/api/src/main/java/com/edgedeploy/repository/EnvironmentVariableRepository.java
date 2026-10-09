package com.edgedeploy.repository;

import com.edgedeploy.entity.EnvironmentVariable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EnvironmentVariableRepository extends JpaRepository<EnvironmentVariable, UUID> {

    List<EnvironmentVariable> findAllByProject_IdOrderByKeyAsc(UUID projectId);

    Optional<EnvironmentVariable> findByProject_IdAndKey(UUID projectId, String key);

    long countByProject_Id(UUID projectId);
}
