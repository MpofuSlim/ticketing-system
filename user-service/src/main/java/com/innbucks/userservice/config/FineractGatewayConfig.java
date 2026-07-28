package com.innbucks.userservice.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers {@link FineractGatewayProperties} (prefix {@code fineract-gateway}). */
@Configuration
@EnableConfigurationProperties(FineractGatewayProperties.class)
public class FineractGatewayConfig {
}
