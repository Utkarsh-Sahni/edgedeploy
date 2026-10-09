package com.edgedeploy.worker.aws;

import com.edgedeploy.worker.delivery.DeliveryException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.Parameter;
import software.amazon.awssdk.services.ssm.model.ParameterType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Keeps a project's environment variables as SSM Parameter Store SecureString parameters
 * ({@code /edgedeploy/{projectId}/env/{KEY}}, encrypted with the account's {@code aws/ssm} KMS key).
 *
 * <p>The task definition then only contains parameter ARNs ({@code secrets}), which ECS resolves when a task
 * starts. Values never appear in task definitions, which are kept forever per revision and are readable by
 * anyone with {@code ecs:DescribeTaskDefinition}. Standard-tier parameters are free.
 */
@Component
@ConditionalOnProperty(prefix = "edgedeploy.aws", name = "enabled", havingValue = "true")
public class SsmEnvironmentStore {

    private final SsmClient ssm;
    private final AwsResourceNames names;
    private final String arnPrefix;

    public SsmEnvironmentStore(SsmClient ssm, AwsResourceNames names, AwsProperties properties) {
        this.ssm = ssm;
        this.names = names;
        this.arnPrefix = "arn:aws:ssm:" + properties.region() + ":" + properties.accountId() + ":parameter";
    }

    /**
     * Writes the variables and removes parameters for variables that no longer exist.
     *
     * @return variable name -> parameter ARN, for the task definition's {@code secrets}
     */
    public Map<String, String> sync(UUID projectId, Map<String, String> variables) throws DeliveryException {
        Map<String, String> references = new LinkedHashMap<>();
        try {
            for (Map.Entry<String, String> variable : variables.entrySet()) {
                String name = names.parameterName(projectId, variable.getKey());
                ssm.putParameter(r -> r.name(name).value(variable.getValue()).type(ParameterType.SECURE_STRING).overwrite(true));
                references.put(variable.getKey(), arnPrefix + name);
            }
        } catch (SdkException e) {
            throw AwsErrors.translate("ssm:PutParameter", e);
        }
        try {
            List<String> stale = new ArrayList<>();
            // GetParametersByPath (unlike DescribeParameters) can be scoped to /edgedeploy/* in IAM.
            ssm.getParametersByPathPaginator(r -> r.path(names.parameterPath(projectId).replaceAll("/$", "")).recursive(false).withDecryption(false))
                    .stream()
                    .flatMap(page -> page.parameters().stream())
                    .map(Parameter::name)
                    .filter(name -> !references.containsKey(name.substring(name.lastIndexOf('/') + 1)))
                    .forEach(stale::add);
            for (int i = 0; i < stale.size(); i += 10) {
                List<String> batch = stale.subList(i, Math.min(i + 10, stale.size()));
                ssm.deleteParameters(r -> r.names(batch));
            }
        } catch (SdkException e) {
            throw AwsErrors.translate("ssm:DeleteParameters", e);
        }
        return references;
    }
}
