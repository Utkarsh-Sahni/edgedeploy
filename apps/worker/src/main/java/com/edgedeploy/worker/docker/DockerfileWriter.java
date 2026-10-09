package com.edgedeploy.worker.docker;

import com.edgedeploy.worker.framework.DetectedProject;
import com.edgedeploy.worker.framework.PackageManager;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Tiny Dockerfile builder that makes injection impossible by construction: every command is emitted in
 * JSON exec form ({@code RUN ["sh", "-c", "..."]}) with JSON escaping, so a user-supplied command can
 * never end the line, continue onto the next instruction, or add new instructions.
 */
final class DockerfileWriter {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** Image references we interpolate (from configuration): registry/name:tag@digest characters only. */
    private static final Pattern IMAGE = Pattern.compile("^[a-z0-9][a-z0-9._/:@-]*$");
    private static final Pattern COPY_SOURCE = Pattern.compile("^[A-Za-z0-9._-]+$");
    /** Mirrors the api's validation: one line, no control characters. Re-checked because the worker trusts nothing. */
    private static final Pattern COMMAND = Pattern.compile("^[^\\p{Cntrl}]{1,500}$");

    private final StringBuilder out = new StringBuilder();

    DockerfileWriter comment(String text) {
        out.append("# ").append(text.replace('\n', ' ')).append('\n');
        return this;
    }

    DockerfileWriter blank() {
        out.append('\n');
        return this;
    }

    DockerfileWriter from(String image, String stage) {
        requireImage(image);
        out.append("FROM ").append(image).append(" AS ").append(stage).append('\n');
        return this;
    }

    DockerfileWriter raw(String instruction) {
        out.append(instruction).append('\n');
        return this;
    }

    /** COPY of fixed, validated file names from the build context. */
    DockerfileWriter copyFiles(List<String> files, String destination) {
        files.forEach(f -> {
            if (!COPY_SOURCE.matcher(f).matches()) {
                throw new IllegalArgumentException("Unsafe COPY source: " + f);
            }
        });
        out.append("COPY [");
        for (String file : files) {
            out.append(json(file)).append(", ");
        }
        out.append(json(destination)).append("]\n");
        return this;
    }

    DockerfileWriter run(String command) {
        out.append("RUN ").append(execForm(command)).append('\n');
        return this;
    }

    DockerfileWriter cmd(String command) {
        out.append("CMD ").append(execForm(command)).append('\n');
        return this;
    }

    @Override
    public String toString() {
        return out.toString();
    }

    static String execForm(String command) {
        return json(new String[]{"sh", "-c", command});
    }

    static void requireCommand(String command, String what) throws BuildConfigurationException {
        if (command != null && !COMMAND.matcher(command).matches()) {
            throw new BuildConfigurationException(what + " must be a single line of at most 500 characters");
        }
    }

    /** Copy dependency manifests first so the install layer is cached until they change. */
    static DockerfileWriter installDependencies(DockerfileWriter writer, DetectedProject project) {
        if (project.packageManager() == PackageManager.YARN_BERRY) {
            // Yarn 2+ installs depend on .yarn/ (plugins, releases); copy the whole tree first.
            writer.raw("COPY . .");
        } else {
            writer.copyFiles(project.manifestFiles(), "./");
        }
        return writer.run(project.packageManager().installCommand(project.hasLockfile()));
    }

    private static void requireImage(String image) {
        if (!IMAGE.matcher(image).matches()) {
            throw new IllegalArgumentException("Invalid image reference: " + image);
        }
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
