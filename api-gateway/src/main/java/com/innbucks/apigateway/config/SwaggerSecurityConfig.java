package com.innbucks.apigateway.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint;
import org.springframework.security.web.server.authorization.HttpStatusServerAccessDeniedHandler;

import static org.springframework.security.config.Customizer.withDefaults;

/**
 * Puts an HTTP Basic login in front of the aggregated Swagger UI so the API
 * surface isn't browsable by anyone who reaches the gateway. Only the gateway
 * is published publicly ({@code 0.0.0.0:18080}); every backend service binds
 * its own port to {@code 127.0.0.1}, so the gateway's Swagger is the only
 * publicly reachable copy and protecting it here is sufficient.
 *
 * <p>Scope is deliberately narrow — see {@link #SWAGGER_PATHS}. Only the UI,
 * its static assets, and the OpenAPI JSON the UI fetches through the gateway
 * require the Basic credentials. All other exchanges are {@code permitAll}
 * here: routed API traffic ({@code /auth/**}, {@code /events/**}, …) is
 * authenticated by the downstream services' JWT filters exactly as before, so
 * adding the security starter does not change how the proxied API is secured.
 *
 * <p>Credentials come from {@code SWAGGER_USER} (default {@code admin}) and
 * {@code SWAGGER_PASSWORD}. Behaviour depends on the password and the active
 * profile:
 * <ul>
 *   <li><b>password set</b> (any profile) — Swagger is gated behind the HTTP
 *       Basic login.</li>
 *   <li><b>password blank, {@code prod} profile</b> — Swagger fails
 *       <i>closed</i>: the paths are denied and answer {@code 404}, so a prod
 *       deploy that forgot the secret hides the docs instead of leaking them.
 *       Set {@code SWAGGER_PASSWORD} to expose them behind the login.</li>
 *   <li><b>password blank, non-prod</b> — Swagger is left open (dev/uat
 *       convenience) and a loud warning is logged.</li>
 * </ul>
 */
@Configuration
@EnableWebFluxSecurity
@Slf4j
public class SwaggerSecurityConfig {

    /**
     * Paths that require the Basic login:
     * <ul>
     *   <li>{@code /swagger-ui.html}, {@code /swagger-ui/**} — the UI itself,</li>
     *   <li>{@code /v3/api-docs/**} — the aggregator's own config + docs,</li>
     *   <li>{@code /webjars/**} — Swagger UI's bundled JS/CSS,</li>
     *   <li>{@code /*-service/v3/api-docs(/**)} — each backend's OpenAPI JSON,
     *       proxied through the gateway and listed in the UI's url dropdown.</li>
     * </ul>
     */
    static final String[] SWAGGER_PATHS = {
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/v3/api-docs/**",
            "/webjars/**",
            "/*-service/v3/api-docs",
            "/*-service/v3/api-docs/**",
    };

    @Value("${swagger.auth.username:admin}")
    private String swaggerUser;

    @Value("${swagger.auth.password:}")
    private String swaggerPassword;

    private boolean protectionEnabled() {
        return swaggerPassword != null && !swaggerPassword.isBlank();
    }

    @Bean
    public SecurityWebFilterChain swaggerSecurityWebFilterChain(ServerHttpSecurity http, Environment environment) {
        // CSRF off: this is a stateless API gateway, not a session/form app —
        // CSRF tokens would break the proxied POST/PUT/DELETE routes. formLogin
        // off so an unauthenticated swagger hit gets the Basic prompt, not a
        // redirect to a (non-existent) login page.
        http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable);

        if (protectionEnabled()) {
            http
                    .authorizeExchange(ex -> ex
                            .pathMatchers(SWAGGER_PATHS).authenticated()
                            .anyExchange().permitAll())
                    .httpBasic(withDefaults());
        } else if (environment.acceptsProfiles(Profiles.of("prod"))) {
            // Fail closed in prod: no password means the docs must NOT be
            // browsable. Deny the Swagger paths and answer 404 so the docs look
            // absent rather than advertising a gated resource. The operator opts
            // back in by setting SWAGGER_PASSWORD.
            log.warn("swagger.auth.password is not set under the 'prod' profile — the Swagger UI "
                    + "at the gateway is DISABLED (returns 404). Set SWAGGER_PASSWORD to expose it "
                    + "behind an HTTP Basic login.");
            http
                    .authorizeExchange(ex -> ex
                            .pathMatchers(SWAGGER_PATHS).denyAll()
                            .anyExchange().permitAll())
                    .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                    .exceptionHandling(ex -> ex
                            .authenticationEntryPoint(new HttpStatusServerEntryPoint(HttpStatus.NOT_FOUND))
                            .accessDeniedHandler(new HttpStatusServerAccessDeniedHandler(HttpStatus.NOT_FOUND)));
        } else {
            log.warn("swagger.auth.password is not set — the Swagger UI at the gateway is NOT "
                    + "password-protected. Set SWAGGER_PASSWORD (and optionally SWAGGER_USER) to "
                    + "require a login.");
            http
                    .authorizeExchange(ex -> ex.anyExchange().permitAll())
                    .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable);
        }
        return http.build();
    }

    /**
     * The single in-memory account that may view Swagger. Created even when no
     * password is configured (httpBasic is disabled in that branch, so it's
     * inert) to keep the bean graph stable; the password is BCrypt-hashed via
     * {@link #swaggerPasswordEncoder()}.
     */
    @Bean
    public MapReactiveUserDetailsService swaggerUserDetailsService(PasswordEncoder encoder) {
        String rawPassword = protectionEnabled() ? swaggerPassword : "disabled";
        UserDetails user = User.withUsername(swaggerUser)
                .password(encoder.encode(rawPassword))
                .roles("SWAGGER")
                .build();
        return new MapReactiveUserDetailsService(user);
    }

    @Bean
    public PasswordEncoder swaggerPasswordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
