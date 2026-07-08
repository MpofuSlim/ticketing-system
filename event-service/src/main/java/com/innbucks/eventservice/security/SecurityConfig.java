package com.innbucks.eventservice.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
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
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/error").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/swagger-ui/**").permitAll()
                        .requestMatchers("/swagger-ui.html").permitAll()
                        .requestMatchers("/v3/api-docs/**").permitAll()
                        // Inactive = drafts + admin-rejected events: authenticated only
                        // (URL-level backstop for the @PreAuthorize on GET /events/inactive,
                        // so a dropped annotation can't silently expose it). MUST precede
                        // the broad /events/** permitAll below — first match wins.
                        .requestMatchers(HttpMethod.GET, "/events/inactive").authenticated()
                        .requestMatchers(HttpMethod.GET, "/events").permitAll()
                        .requestMatchers(HttpMethod.GET, "/events/**").permitAll()
                        // Internal: booking-service decrements availability on confirm.
                        .requestMatchers(HttpMethod.PATCH, "/events/*/availability/consume").permitAll()
                        // Internal: booking-service restores availability on reverse of a
                        // confirmed booking (admin refund, future veengu real-payment
                        // failure compensation). Controller enforces X-Internal-Token.
                        .requestMatchers(HttpMethod.PATCH, "/events/*/availability/release").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .anyRequest().authenticated()
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setContentType("application/json");
                            response.setStatus(401);
                            response.getWriter().write(
                                    "{\"code\":\"401 UNAUTHORIZED\",\"message\":\"Invalid token\",\"data\":null}");
                        })
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            response.setContentType("application/json");
                            response.setStatus(403);
                            response.getWriter().write(
                                    "{\"code\":\"403 FORBIDDEN\",\"message\":\"Forbidden - you do not have the required role (EVENT_ORGANIZER or SUPER_ADMIN)\",\"data\":null}");
                        })
                )
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
