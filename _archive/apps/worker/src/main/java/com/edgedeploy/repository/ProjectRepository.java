package com.edgedeploy.repository;

import com.edgedeploy.entity.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectRepository extends JpaRepository<Project, UUID> {

    List<Project> findByUserIdOrderByUpdatedAtDesc(UUID userId);

    Optional<Project> findByIdAndUserId(UUID id, UUID userId);

    Optional<Project> findByUserIdAndRepository(UUID userId, String repository);

    Optional<Project> findByRepository(String repository);

    @Query("select p from Project p join fetch p.user where p.id = :id")
    Optional<Project> findWithUserById(@Param("id") UUID id);
}
