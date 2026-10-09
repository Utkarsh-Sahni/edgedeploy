package com.edgedeploy.contracts;

public final class KafkaTopics {

    /** Command: a deployment row exists in QUEUED and should be executed. Key = deploymentId. */
    public static final String DEPLOYMENT_REQUESTED = "deployment.requested";

    /** Notification: a deployment changed state. Key = deploymentId. Postgres remains the source of truth. */
    public static final String DEPLOYMENT_STATUS = "deployment.status";

    /** Notification: new deployment log lines were persisted. Key = deploymentId. */
    public static final String DEPLOYMENT_LOGS = "deployment.logs";

    /** Business event: a deployment ended in FAILED. Not to be confused with the dead-letter topic. */
    public static final String DEPLOYMENT_FAILED = "deployment.failed";

    /** Poison/unprocessable deployment.requested records after retries are exhausted. */
    public static final String DEPLOYMENT_REQUESTED_DLT = DEPLOYMENT_REQUESTED + ".DLT";

    private KafkaTopics() {
    }
}
