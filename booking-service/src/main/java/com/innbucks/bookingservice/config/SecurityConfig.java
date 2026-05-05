package com.innbucks.bookingservice.config;

import com.innbucks.bookingservice.security.JwtFilter;
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

    @Bean
    public SecurityFilterChain apiChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/swagger-ui/**").permitAll()
                        .requestMatchers("/swagger-ui.html").permitAll()
                        .requestMatchers("/v3/api-docs/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        // Confirmation lookup is public — agents scan at the gate
                        .requestMatchers("/bookings/confirmation/**").permitAll()
                        // Guest web flow: customers can book without logging
                        // in. JWT is optional — when present, the customer's
                        // tier is enforced by TierAccessInterceptor. When
                        // absent, the controller treats them as a guest.
                        .requestMatchers(HttpMethod.GET, "/bookings/phone/**").permitAll()
                        // Internal endpoint: event-service reads it to compute
                        // availableTickets on every event response.
                        .requestMatchers(HttpMethod.GET, "/bookings/active-counts").permitAll()
                        .requestMatchers(HttpMethod.POST, "/bookings").permitAll()
                        // Confirm is called by payment-service after a (dummy)
                        // payment. Guests have no JWT, so the endpoint must
                        // be reachable without one.
                        .requestMatchers(HttpMethod.PATCH, "/bookings/*/confirm").permitAll()
                        // H2 console (dev only). frameOptions=sameOrigin above
                        // lets it render its iframes.
                        .requestMatchers("/h2-console/**").permitAll()
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
}
