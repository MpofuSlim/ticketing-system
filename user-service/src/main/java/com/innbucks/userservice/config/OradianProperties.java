package com.innbucks.userservice.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "oradian")
public class OradianProperties {
    private String baseUrl;
    private String internalToken;
    private int connectTimeoutMs = 2000;
    private int readTimeoutMs = 10000;
}
