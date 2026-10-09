package com.edgedeploy.repository;

import com.edgedeploy.entity.GitHubCredential;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface GitHubCredentialRepository extends JpaRepository<GitHubCredential, UUID> {
}
