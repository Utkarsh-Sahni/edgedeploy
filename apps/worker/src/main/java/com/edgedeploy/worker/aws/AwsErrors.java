package com.edgedeploy.worker.aws;

import com.edgedeploy.worker.delivery.DeliveryException;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkException;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns AWS SDK failures into user-facing {@link DeliveryException}s: specific enough to act on ("not
 * authorized to perform ecs:UpdateService"), but without ARNs, account ids or request ids.
 *
 * <p>By the time an exception reaches this class the SDK's retry strategy has already retried it if it
 * was transient (throttling, 5xx, network); everything here is final.
 */
public final class AwsErrors {

    private static final Pattern ARN = Pattern.compile("arn:aws[a-z-]*:[^\\s\"',;)]+");
    private static final Pattern ACCOUNT_ID = Pattern.compile("\\b\\d{12}\\b");

    private AwsErrors() {
    }

    /** @param action the AWS API action, e.g. {@code ecs:UpdateService} */
    public static DeliveryException translate(String action, SdkException e) {
        if (e instanceof AwsServiceException service && service.awsErrorDetails() != null) {
            String code = String.valueOf(service.awsErrorDetails().errorCode());
            String lower = code.toLowerCase(Locale.ROOT);
            if (lower.contains("accessdenied") || lower.contains("unauthorized") || lower.equals("authfailure")) {
                return new DeliveryException("The EdgeDeploy worker is not authorized to perform " + action
                        + ". Check the worker's IAM policy (docs/aws-setup.md).", e);
            }
            if (lower.contains("expiredtoken") || lower.contains("invalidclienttokenid")
                    || lower.contains("unrecognizedclient") || lower.contains("signaturedoesnotmatch")) {
                return new DeliveryException("The worker's AWS credentials are invalid or expired (" + action + ").", e);
            }
            if (service.isThrottlingException() || lower.contains("throttl") || lower.contains("toomanyrequests")
                    || lower.contains("requestlimitexceeded")) {
                return new DeliveryException("AWS rate limit exceeded during " + action + " even after retries. Try again shortly.", e);
            }
            String message = sanitize(service.awsErrorDetails().errorMessage());
            return new DeliveryException("AWS rejected " + action + " (" + code + ")"
                    + (message.isBlank() ? "" : ": " + message), e);
        }
        if (e instanceof SdkClientException) {
            return new DeliveryException("Could not reach AWS for " + action + " (network error or timeout).", e);
        }
        return new DeliveryException("AWS request " + action + " failed.", e);
    }

    /** Removes ARNs and account ids from AWS error messages before they are shown to users. */
    static String sanitize(String message) {
        if (message == null) {
            return "";
        }
        String clean = ARN.matcher(message).replaceAll("<resource>");
        clean = ACCOUNT_ID.matcher(clean).replaceAll("<account>");
        return clean.length() <= 300 ? clean : clean.substring(0, 300) + "…";
    }
}
