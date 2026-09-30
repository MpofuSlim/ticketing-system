package com.innbucks.userservice.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers {@link StaffAccountProperties} (bound from {@code staff.*}, validated at boot). */
@Configuration
@EnableConfigurationProperties(StaffAccountProperties.class)
public class StaffAccountsConfig {
}
