package com.edgedeploy.repository;

import com.edgedeploy.entity.DeploymentLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DeploymentLogRepository extends JpaRepository<DeploymentLog, UUID> {

    List<DeploymentLog> findByDeploymentIdOrderByTimestampAsc(UUID deploymentId);
}
