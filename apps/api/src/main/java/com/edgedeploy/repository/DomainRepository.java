package com.edgedeploy.repository;

import com.edgedeploy.entity.Domain;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DomainRepository extends JpaRepository<Domain, UUID> {

    List<Domain> findAllByProject_Id(UUID projectId);
}
