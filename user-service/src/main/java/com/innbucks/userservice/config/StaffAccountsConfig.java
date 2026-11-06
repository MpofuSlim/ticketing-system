package com.innbucks.userservice.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link StaffAccountProperties} (bound from {@code staff.*}) and
 * {@link SalesAgentInviteProperties} (bound from {@code sales-agent-invite.*},
 * V56), both validated at boot.
 */
@Configuration
@EnableConfigurationProperties({StaffAccountProperties.class, SalesAgentInviteProperties.class})
public class StaffAccountsConfig {
}
