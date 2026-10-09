package com.edgedeploy.service;

import com.edgedeploy.dto.CurrentUserResponse;
import com.edgedeploy.exception.ResourceNotFoundException;
import com.edgedeploy.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class UserService {

    private final UserRepository users;

    public UserService(UserRepository users) {
        this.users = users;
    }

    @Transactional(readOnly = true)
    public CurrentUserResponse get(UUID userId) {
        return users.findById(userId)
                .map(u -> new CurrentUserResponse(u.getId(), u.getGithubLogin(), u.getName(), u.getEmail(), u.getAvatarUrl()))
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
    }
}
