package com.innbucks.bookingservice.config;

import com.innbucks.bookingservice.security.JwtFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtFilter jwtFilter;

    // Until the corresponding CorsConfigurationSource bean below landed,
    // the `cors.allowed-origins` property declared in application.yaml was
    // dead config — direct-to-port browser calls (anything bypassing the
    // gateway) were silently rejected by the browser. Mirrors loyalty-service.
    @Value("${cors.allowed-origins:*}")
    private List<String> allowedOrigins;

    @Bean
    public SecurityFilterChain apiChain(HttpSecurity http) throws Exception {
        http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
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
                        // Confirm is called by payment-service after a payment.
                        // No JWT is involved — payment-service authenticates via
                        // the shared `X-Internal-Token` checked inside
                        // BookingController#confirmBooking. We permit at the
                        // Spring layer so the controller can return a clean
                        // ApiResult body instead of Spring's opaque default 401.
                        .requestMatchers(HttpMethod.PATCH, "/bookings/*/confirm").permitAll()
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
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        // Patterns (not literal origins) so wildcards in `cors.allowed-origins`
        // — e.g. https://*.vercel.app — work alongside allowCredentials=true.
        config.setAllowedOriginPatterns(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
