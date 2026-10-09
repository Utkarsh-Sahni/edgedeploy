package com.edgedeploy.service;

import com.edgedeploy.config.EdgeDeployProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EncryptionServiceTest {

    @Test
    void roundTripsSecrets() {
        EdgeDeployProperties properties = new EdgeDeployProperties();
        properties.getEncryption().setKey("MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDA=");
        EncryptionService service = new EncryptionService(properties);

        String encrypted = service.encrypt("super-secret");
        assertThat(encrypted).isNotEqualTo("super-secret");
        assertThat(service.decrypt(encrypted)).isEqualTo("super-secret");
    }
}
