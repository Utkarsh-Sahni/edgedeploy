package com.edgedeploy.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class AppConfig {

    /** Injected wherever "now" matters so time-dependent logic stays testable. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
