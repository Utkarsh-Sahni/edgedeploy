package com.edgedeploy.repository;

import com.edgedeploy.entity.Project;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectRepository extends JpaRepository<Project, UUID> {

    List<Project> findAllByUser_IdOrderByCreatedAtDesc(UUID userId);

    /** Ownership-scoped lookup: another user's project is indistinguishable from a missing one. */
    Optional<Project> findByIdAndUser_Id(UUID id, UUID userId);

    /** Owned project, row-locked until commit: serialises deployment numbering per project. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Project p where p.id = :id and p.user.id = :userId")
    Optional<Project> findOwnedForUpdate(@Param("id") UUID id, @Param("userId") UUID userId);

    boolean existsByUser_IdAndRepository(UUID userId, String repository);

    boolean existsByUser_IdAndSlug(UUID userId, String slug);
}
