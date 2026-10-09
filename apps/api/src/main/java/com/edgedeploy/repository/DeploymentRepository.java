package com.edgedeploy.repository;

import com.edgedeploy.contracts.DeploymentStatus;
import com.edgedeploy.entity.Deployment;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeploymentRepository extends JpaRepository<Deployment, UUID> {

    @Query("""
            select d from Deployment d
            join fetch d.project p
            where d.id = :id and p.user.id = :userId
            """)
    Optional<Deployment> findOwned(@Param("id") UUID id, @Param("userId") UUID userId);

    List<Deployment> findByProject_IdOrderByCreatedAtDesc(UUID projectId, Limit limit);

    @Query("select coalesce(max(d.number), 0) from Deployment d where d.project.id = :projectId")
    int maxNumber(@Param("projectId") UUID projectId);

    /** The user's most recent deployments across all of their projects (dashboard). */
    @Query("""
            select d from Deployment d
            join fetch d.project p
            where p.user.id = :userId
            order by d.createdAt desc
            """)
    List<Deployment> findRecentForUser(@Param("userId") UUID userId, Limit limit);

    /**
     * Compare-and-set status change; returns 0 if the row is no longer in {@code from}.
     * {@code completedAt} is only filled in once (the first time the deployment settles).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Deployment d
               set d.status = :to, d.updatedAt = :now, d.completedAt = coalesce(d.completedAt, :completedAt)
             where d.id = :id and d.status = :from
            """)
    int compareAndSetStatus(@Param("id") UUID id, @Param("from") DeploymentStatus from, @Param("to") DeploymentStatus to,
                            @Param("now") Instant now, @Param("completedAt") Instant completedAt);
}
