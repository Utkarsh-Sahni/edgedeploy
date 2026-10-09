package com.edgedeploy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "edgedeploy")
public class EdgeDeployProperties {

    private String webOrigin = "http://localhost:3000";
    private final Jwt jwt = new Jwt();
    private final Encryption encryption = new Encryption();
    private final GitHub github = new GitHub();
    private final Kafka kafka = new Kafka();
    private final RateLimit rateLimit = new RateLimit();
    private final Deployment deployment = new Deployment();

    public String getWebOrigin() {
        return webOrigin;
    }

    public void setWebOrigin(String webOrigin) {
        this.webOrigin = webOrigin;
    }

    public Jwt getJwt() {
        return jwt;
    }

    public Encryption getEncryption() {
        return encryption;
    }

    public GitHub getGithub() {
        return github;
    }

    public Kafka getKafka() {
        return kafka;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public Deployment getDeployment() {
        return deployment;
    }

    public static class Jwt {
        private String secret;
        private Duration expiration = Duration.ofHours(8);
        private String cookieName = "edgedeploy_token";

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public Duration getExpiration() {
            return expiration;
        }

        public void setExpiration(Duration expiration) {
            this.expiration = expiration;
        }

        public String getCookieName() {
            return cookieName;
        }

        public void setCookieName(String cookieName) {
            this.cookieName = cookieName;
        }
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

    public static class GitHub {
        private String webhookSecret = "";
        private String apiBaseUrl = "https://api.github.com";

        public String getWebhookSecret() {
            return webhookSecret;
        }

        public void setWebhookSecret(String webhookSecret) {
            this.webhookSecret = webhookSecret;
        }

        public String getApiBaseUrl() {
            return apiBaseUrl;
        }

        public void setApiBaseUrl(String apiBaseUrl) {
            this.apiBaseUrl = apiBaseUrl;
        }
    }

    public static class Kafka {
        private Topics topics = new Topics();

        public Topics getTopics() {
            return topics;
        }

        public void setTopics(Topics topics) {
            this.topics = topics;
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

    public static class RateLimit {
        private int requestsPerMinute = 120;

        public int getRequestsPerMinute() {
            return requestsPerMinute;
        }

        public void setRequestsPerMinute(int requestsPerMinute) {
            this.requestsPerMinute = requestsPerMinute;
        }
    }

    public static class Deployment {
        private String baseDomain = "localhost";

        public String getBaseDomain() {
            return baseDomain;
        }

        public void setBaseDomain(String baseDomain) {
            this.baseDomain = baseDomain;
        }
    }
}
