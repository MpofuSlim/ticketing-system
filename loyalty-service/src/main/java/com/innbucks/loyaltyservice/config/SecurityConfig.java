package com.innbucks.loyaltyservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
public class SecurityConfig {

    // Comma-separated; Spring binds a String -> List<String> automatically.
    // Default is permissive ('*') so dev/Swagger/ngrok/any FE origin works
    // out of the box. Safe with allowCredentials=true because we use
    // setAllowedOriginPatterns (not setAllowedOrigins). MUST be overridden
    // in prod via CORS_ALLOWED_ORIGINS to your real client origins
    // (e.g. https://app.example.com).
    @Value("${cors.allowed-origins:*}")
    private List<String> allowedOrigins;

    /**
     * The loyalty service sits behind the API gateway, which authenticates the
     * caller upstream and forwards trusted headers. We keep CSRF off (stateless
     * REST), allow actuator, swagger, and the loyalty API; everything else
     * still requires authentication if it ever gets exposed directly.
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
                                "/actuator/**",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/loyalty/**"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .httpBasic(b -> {});
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
