package com.edgedeploy.docker;

import com.edgedeploy.config.WorkerProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

@Service
public class LocalContainerRuntime {

    private final ProcessRunner processRunner;
    private final WorkerProperties properties;

    public LocalContainerRuntime(ProcessRunner processRunner, WorkerProperties properties) {
        this.processRunner = processRunner;
        this.properties = properties;
    }

    public String deploy(UUID deploymentId, String imageTag, int port, Map<String, String> env, Consumer<String> logSink)
            throws Exception {
        String name = "edgedeploy-" + deploymentId;
        try {
            processRunner.run(List.of("docker", "rm", "-f", name), logSink);
        } catch (Exception ignored) {
            // container may not exist yet
        }

        String hostname = hostname(deploymentId);
        List<String> command = new ArrayList<>();
        command.addAll(List.of(
                "docker", "run", "-d",
                "--name", name,
                "--network", properties.getDockerNetwork(),
                "--label", "traefik.enable=true",
                "--label", "traefik.http.routers." + name + ".rule=Host(`" + hostname + "`)",
                "--label", "traefik.http.services." + name + ".loadbalancer.server.port=" + port
        ));
        env.forEach((key, value) -> {
            command.add("-e");
            command.add(key + "=" + value);
        });
        command.add(imageTag);
        processRunner.run(command, logSink);
        return properties.getDeployment().getScheme() + "://" + hostname;
    }

    public void stop(UUID deploymentId) throws Exception {
        processRunner.run(List.of("docker", "rm", "-f", "edgedeploy-" + deploymentId), ignored -> {
        });
    }

    public String hostname(UUID deploymentId) {
        return deploymentId.toString().substring(0, 8) + "." + properties.getDeployment().getBaseDomain();
    }
}
