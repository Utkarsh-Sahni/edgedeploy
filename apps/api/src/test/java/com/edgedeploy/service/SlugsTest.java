package com.edgedeploy.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class SlugsTest {

    @ParameterizedTest
    @CsvSource({
            "My Portfolio, my-portfolio",
            "  --Hello__World!!  , hello-world",
            "Café Déjà Vu, cafe-deja-vu",
            "API v2.0, api-v2-0",
    })
    void slugifiesToDnsSafeLabels(String input, String expected) {
        assertThat(Slugs.slugify(input)).isEqualTo(expected);
    }

    @Test
    void fallsBackWhenNothingUsableRemains() {
        assertThat(Slugs.slugify("🚀🚀")).isEqualTo("app");
    }

    @Test
    void truncatesWithoutTrailingHyphen() {
        String slug = Slugs.slugify("a".repeat(39) + " bbbbbb");
        assertThat(slug).hasSizeLessThanOrEqualTo(Slugs.MAX_LENGTH).doesNotEndWith("-");
    }
}
