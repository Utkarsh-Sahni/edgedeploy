package com.edgedeploy.worker.aws;

/**
 * Tags put on every AWS resource EdgeDeploy creates: they identify the owning project (ownership is verified
 * before a service is updated) and let the cleanup script find everything EdgeDeploy made.
 */
public final class AwsTags {

    public static final String PROJECT = "edgedeploy:project-id";
    public static final String MANAGED = "edgedeploy:managed";

    private AwsTags() {
    }
}
