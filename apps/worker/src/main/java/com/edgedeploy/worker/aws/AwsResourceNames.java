package com.edgedeploy.worker.aws;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Deterministic AWS resource names, derived from the project id only. Re-running a deployment (or a
 * duplicate Kafka message) always addresses the same resources, never creates look-alikes, and can never
 * touch another project's resources. Each name is checked against the AWS naming rules.
 */
public final class AwsResourceNames {

    private static final Pattern ECR_REPOSITORY = Pattern.compile("^(?:[a-z0-9]+(?:[._-][a-z0-9]+)*/)*[a-z0-9]+(?:[._-][a-z0-9]+)*$");
    private static final Pattern ECS_NAME = Pattern.compile("^[A-Za-z0-9_-]{1,255}$");
    /** ALB target groups: at most 32 alphanumerics/hyphens, not starting or ending with a hyphen. */
    private static final Pattern TARGET_GROUP = Pattern.compile("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,30}[A-Za-z0-9])?$");
    private static final Pattern SSM_KEY = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,127}$");

    private final String ecrPrefix;
    private final String logGroupPrefix;

    public AwsResourceNames(AwsProperties properties) {
        this(properties.ecr().repositoryPrefix(), properties.cloudwatch().logGroupPrefix());
    }

    AwsResourceNames(String ecrPrefix, String logGroupPrefix) {
        this.ecrPrefix = ecrPrefix.replaceAll("/+$", "");
        this.logGroupPrefix = logGroupPrefix.replaceAll("/+$", "");
    }

    /** {@code edgedeploy/{projectId}}: one repository per project (see docs/aws-setup.md for the tradeoff). */
    public String ecrRepository(UUID projectId) {
        return check(ecrPrefix + "/" + projectId, ECR_REPOSITORY, 256, "ECR repository");
    }

    /** {@code edgedeploy-{projectId}}: one long-lived ECS service per project, updated by each deployment. */
    public String ecsService(UUID projectId) {
        return check("edgedeploy-" + projectId, ECS_NAME, 255, "ECS service");
    }

    /** Task definition family; every deployment registers a new revision of it. */
    public String taskDefinitionFamily(UUID projectId) {
        return check("edgedeploy-" + projectId, ECS_NAME, 255, "task definition family");
    }

    /** {@code ed-} + 29 hex characters of the project id: target group names are limited to 32 characters. */
    public String targetGroup(UUID projectId) {
        String hex = projectId.toString().replace("-", "").toLowerCase(Locale.ROOT);
        return check("ed-" + hex.substring(0, 29), TARGET_GROUP, 32, "target group");
    }

    /** {@code /edgedeploy/{projectId}}. */
    public String logGroup(UUID projectId) {
        return logGroupPrefix + "/" + projectId;
    }

    /** SSM parameter path holding a project's environment variables. */
    public String parameterPath(UUID projectId) {
        return "/edgedeploy/" + projectId + "/env/";
    }

    public String parameterName(UUID projectId, String key) {
        if (!SSM_KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Invalid environment variable name");
        }
        return parameterPath(projectId) + key;
    }

    private static String check(String name, Pattern pattern, int maxLength, String what) {
        if (name.length() > maxLength || !pattern.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid " + what + " name: " + name);
        }
        return name;
    }
}
