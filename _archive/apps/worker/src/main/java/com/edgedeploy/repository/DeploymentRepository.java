package com.edgedeploy.repository;

import com.edgedeploy.entity.Deployment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeploymentRepository extends JpaRepository<Deployment, UUID> {

    List<Deployment> findByProjectIdOrderByCreatedAtDesc(UUID projectId);

    Optional<Deployment> findByIdAndProjectUserId(UUID id, UUID userId);

    Optional<Deployment> findFirstByProjectIdOrderByCreatedAtDesc(UUID projectId);
}
