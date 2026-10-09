package com.edgedeploy.worker.delivery;

/**
 * Publishing or deploying an image failed. The message is user-facing: specific, but free of credentials,
 * account ids and resource ARNs.
 */
public class DeliveryException extends Exception {

    public DeliveryException(String message) {
        super(message);
    }

    public DeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
