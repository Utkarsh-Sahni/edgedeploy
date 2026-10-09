package com.edgedeploy.repository;

import com.edgedeploy.entity.DeploymentLog;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DeploymentLogRepository extends JpaRepository<DeploymentLog, UUID> {

    /** Lines after {@code afterSeq} in insert order; {@code afterSeq = 0} returns everything. */
    List<DeploymentLog> findByDeploymentIdAndSeqGreaterThanOrderBySeqAsc(UUID deploymentId, long afterSeq, Limit limit);
}
