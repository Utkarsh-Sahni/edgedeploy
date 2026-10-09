package com.edgedeploy.docker;

import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

@Service
public class DockerBuildService {

    private final ProcessRunner processRunner;

    public DockerBuildService(ProcessRunner processRunner) {
        this.processRunner = processRunner;
    }

    public void build(Path projectDir, String imageTag, Consumer<String> logSink) throws Exception {
        processRunner.run(List.of("docker", "build", "-t", imageTag, projectDir.toString()), logSink);
    }

    public void push(String imageTag, Consumer<String> logSink) throws Exception {
        processRunner.run(List.of("docker", "push", imageTag), logSink);
    }

    public void login(String registry, String username, String password) throws Exception {
        ProcessBuilder builder = new ProcessBuilder("docker", "login", "--username", username, "--password-stdin", registry);
        Process process = builder.start();
        process.getOutputStream().write(password.getBytes());
        process.getOutputStream().close();
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("docker login failed");
        }
    }
}
