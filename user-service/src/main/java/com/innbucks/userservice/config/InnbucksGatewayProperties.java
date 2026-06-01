package com.innbucks.userservice.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Config for the InnBucks core gateway adapter (SMS, payments). It is a
 * host-resident service (not in the ticketing Eureka), so it is reached via
 * an explicit {@code base-url}, never {@code lb://}.
 */
@Data
@ConfigurationProperties(prefix = "innbucks-gateway")
public class InnbucksGatewayProperties {
    /** Gateway base URL, e.g. http://10.0.155.69:8088 (no trailing path). */
    private String baseUrl;
    private int connectTimeoutMs = 2000;
    private int readTimeoutMs = 10000;
}
