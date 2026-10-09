package com.edgedeploy.deployment;

import com.edgedeploy.entity.Deployment;
import com.edgedeploy.entity.DeploymentLog;
import com.edgedeploy.repository.DeploymentLogRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
public class DeploymentLogWriter {

    private final DeploymentLogRepository repository;
    private final ThreadLocal<List<String>> buffer = ThreadLocal.withInitial(ArrayList::new);

    public DeploymentLogWriter(DeploymentLogRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void append(Deployment deployment, String level, String message) {
        buffer.get().add(message);
        DeploymentLog log = new DeploymentLog();
        log.setDeployment(deployment);
        log.setLevel(level);
        log.setMessage(truncate(message));
        log.setTimestamp(Instant.now());
        repository.save(log);
    }

    public String collected() {
        return String.join("\n", buffer.get());
    }

    public void clear() {
        buffer.remove();
    }

    private String truncate(String message) {
        if (message.length() <= 4000) {
            return message;
        }
        return message.substring(0, 4000);
    }
}
