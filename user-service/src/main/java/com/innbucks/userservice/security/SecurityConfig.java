package com.innbucks.userservice.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtFilter jwtFilter;

    // CORS lives exclusively on the api-gateway (globalcors + RemoveResponseHeader
    // filters per PR #182). Browsers only ever talk to the gateway, so a per-service
    // CorsConfigurationSource here would just emit a second set of headers that
    // collide with the gateway's and trip its DefaultCorsProcessor with "Invalid
    // CORS request". Don't re-introduce a service-level CORS config without also
    // un-doing the gateway-side strip.

    @Bean
    public SecurityFilterChain apiChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .headers(headers -> headers
                        .frameOptions(frame -> frame.sameOrigin())
                )
                .authorizeHttpRequests(auth -> auth
                        // Preflight must be allowed unauthenticated, otherwise the
                        // browser's CORS check fails before the real request runs.
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // Account-ID lookup for send-money MUST be an authenticated
                        // sender. More specific than the /auth/** permitAll below, so
                        // it must come first. Closes the unauthenticated PII / Oradian
                        // account-ID enumeration (audit H1).
                        .requestMatchers(HttpMethod.GET, "/auth/customer/send-money/details/**").authenticated()
                        // Service-to-service tenant lookup. Authenticated by the
                        // shared X-Internal-Token header checked inside
                        // InternalTenantLookupController, not a user JWT — and
                        // blocked at the gateway edge (user-internal-deny route)
                        // so it's only reachable pod-to-pod. Must precede the
                        // catch-all authenticated() rule below.
                        .requestMatchers("/users/internal/**").permitAll()
                        // Public auth endpoints
                        .requestMatchers("/auth/**").permitAll()
                        // Swagger UI
                        .requestMatchers("/swagger-ui/**").permitAll()
                        .requestMatchers("/swagger-ui.html").permitAll()
                        .requestMatchers("/v3/api-docs/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        // Everything else requires authentication
                        .anyRequest().authenticated()
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, e) -> {
                            res.setContentType("application/json");
                            res.setStatus(401);
                            res.getWriter().write(
                                    "{\"code\":\"401 UNAUTHORIZED\",\"message\":\"Invalid token\",\"data\":null}"
                            );
                        })
                        .accessDeniedHandler((req, res, e) -> {
                            res.setContentType("application/json");
                            res.setStatus(403);
                            res.getWriter().write(
                                    "{\"code\":\"403 FORBIDDEN\",\"message\":\"Forbidden - insufficient role\",\"data\":null}"
                            );
                        })
                )
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
