package com.innbucks.discoveryserver;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Secures the Eureka registry. Without this, anything that can reach
 * discovery-server:8761 can register a rogue instance and hijack lb:// traffic
 * (audit CR-2).
 *
 * <p>Eureka clients register / heartbeat via POST/PUT/DELETE with no CSRF token,
 * so CSRF is disabled. Under the {@code prod} profile the registry requires HTTP
 * Basic auth — clients supply credentials in their {@code defaultZone} URL
 * (EUREKA_USERNAME / EUREKA_PASSWORD). In dev/test it stays open so a local run
 * and the CI slice "just work" (in deployed environments the registry is also on
 * the private innbucks-internal network). {@code /actuator/health} is always
 * permitted for the container healthcheck.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, Environment env) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable);
        if (env.acceptsProfiles(Profiles.of("prod"))) {
            http.authorizeHttpRequests(auth -> auth
                            .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                            .anyRequest().authenticated())
                    .httpBasic(Customizer.withDefaults());
        } else {
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        }
        return http.build();
    }
}
