package com.edgedeploy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "edgedeploy")
public class WorkerProperties {

    private final Encryption encryption = new Encryption();
    private final Kafka kafka = new Kafka();
    private String workspace = "/tmp/edgedeploy-workspace";
    private String dockerNetwork = "edgedeploy";
    private final GitHub github = new GitHub();
    private final Aws aws = new Aws();
    private final Deployment deployment = new Deployment();

    public Encryption getEncryption() {
        return encryption;
    }

    public Kafka getKafka() {
        return kafka;
    }

    public String getWorkspace() {
        return workspace;
    }

    public void setWorkspace(String workspace) {
        this.workspace = workspace;
    }

    public String getDockerNetwork() {
        return dockerNetwork;
    }

    public void setDockerNetwork(String dockerNetwork) {
        this.dockerNetwork = dockerNetwork;
    }

    public GitHub getGithub() {
        return github;
    }

    public Aws getAws() {
        return aws;
    }

    public Deployment getDeployment() {
        return deployment;
    }

    public static class Encryption {
        private String key;

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }
    }

    public static class Kafka {
        private Topics topics = new Topics();

        public Topics getTopics() {
            return topics;
        }

        public static class Topics {
            private String requested = "deployment.requested";
            private String status = "deployment.status";
            private String failed = "deployment.failed";

            public String getRequested() {
                return requested;
            }

            public void setRequested(String requested) {
                this.requested = requested;
            }

            public String getStatus() {
                return status;
            }

            public void setStatus(String status) {
                this.status = status;
            }

            public String getFailed() {
                return failed;
            }

            public void setFailed(String failed) {
                this.failed = failed;
            }
        }
    }

    public static class GitHub {
        private String token = "";

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }
    }

    public static class Aws {
        private boolean enabled;
        private String region = "us-east-1";
        private String accountId = "";
        private String cluster = "edgedeploy";
        private String subnets = "";
        private String securityGroups = "";
        private String executionRoleArn = "";
        private String taskRoleArn = "";
        private String logBucket = "edgedeploy-logs";
        private int cpu = 256;
        private int memory = 512;
        private int containerPort = 3000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region;
        }

        public String getAccountId() {
            return accountId;
        }

        public void setAccountId(String accountId) {
            this.accountId = accountId;
        }

        public String getCluster() {
            return cluster;
        }

        public void setCluster(String cluster) {
            this.cluster = cluster;
        }

        public String getSubnets() {
            return subnets;
        }

        public void setSubnets(String subnets) {
            this.subnets = subnets;
        }

        public String getSecurityGroups() {
            return securityGroups;
        }

        public void setSecurityGroups(String securityGroups) {
            this.securityGroups = securityGroups;
        }

        public String getExecutionRoleArn() {
            return executionRoleArn;
        }

        public void setExecutionRoleArn(String executionRoleArn) {
            this.executionRoleArn = executionRoleArn;
        }

        public String getTaskRoleArn() {
            return taskRoleArn;
        }

        public void setTaskRoleArn(String taskRoleArn) {
            this.taskRoleArn = taskRoleArn;
        }

        public String getLogBucket() {
            return logBucket;
        }

        public void setLogBucket(String logBucket) {
            this.logBucket = logBucket;
        }

        public int getCpu() {
            return cpu;
        }

        public void setCpu(int cpu) {
            this.cpu = cpu;
        }

        public int getMemory() {
            return memory;
        }

        public void setMemory(int memory) {
            this.memory = memory;
        }

        public int getContainerPort() {
            return containerPort;
        }

        public void setContainerPort(int containerPort) {
            this.containerPort = containerPort;
        }
    }

    public static class Deployment {
        private String baseDomain = "localhost";
        private String scheme = "http";

        public String getBaseDomain() {
            return baseDomain;
        }

        public void setBaseDomain(String baseDomain) {
            this.baseDomain = baseDomain;
        }

        public String getScheme() {
            return scheme;
        }

        public void setScheme(String scheme) {
            this.scheme = scheme;
        }
    }
}
