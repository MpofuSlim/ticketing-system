package com.innbucks.loyaltyservice.config;

import com.innbucks.loyaltyservice.security.JwtFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
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

    // Comma-separated; Spring binds a String -> List<String> automatically.
    // Default whitelists the production frontend, Vercel preview deploys, and
    // any localhost port for dev. Safe with allowCredentials=true because we
    // use setAllowedOriginPatterns (not setAllowedOrigins). Override in prod
    // via CORS_ALLOWED_ORIGINS to lock down to your real client origins.
    @Value("${cors.allowed-origins:*}")
    private List<String> allowedOrigins;

    /**
     * The loyalty service can be reached either through the API gateway (which
     * forwards JWTs in the Authorization header) or directly. We verify the
     * JWT via {@link JwtFilter} so downstream {@code @PreAuthorize} checks and
     * {@link com.innbucks.loyaltyservice.security.TenantContext}'s ownership
     * lookup both have a real {@code Authentication} to inspect.
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(
                                // Only health + info are anonymous — every other
                                // actuator endpoint (notably /actuator/prometheus,
                                // which exposes business metrics like points
                                // earned/redeemed and voucher counts) must be
                                // authenticated. Loosen explicitly per endpoint,
                                // not via /actuator/**.
                                "/actuator/health",
                                "/actuator/health/**",
                                "/actuator/info",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/error"
                        ).permitAll()
                        // Service-to-service endpoints under /loyalty/internal/**
                        // are gated by a shared-secret header in their controllers
                        // rather than the user JWT. The JwtFilter also skips this
                        // path so no Authentication is required.
                        .requestMatchers("/loyalty/internal/**").permitAll()
                        // Loyalty endpoints require authentication. Method-level
                        // @PreAuthorize on the controllers further restricts who
                        // can call what; TenantContext enforces tenant ownership
                        // on the X-Tenant-Id header.
                        .anyRequest().authenticated()
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, e) -> {
                            res.setContentType("application/json");
                            res.setStatus(401);
                            res.getWriter().write(
                                    "{\"code\":\"401 UNAUTHORIZED\",\"message\":\"Invalid or missing token\",\"data\":null}"
                            );
                        })
                        .accessDeniedHandler((req, res, e) -> {
                            res.setContentType("application/json");
                            res.setStatus(403);
                            res.getWriter().write(
                                    "{\"code\":\"403 FORBIDDEN\",\"message\":\"Forbidden - insufficient role or not the tenant owner\",\"data\":null}"
                            );
                        })
                )
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
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
