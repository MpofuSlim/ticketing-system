package innbucks.paymentservice.config;

import innbucks.paymentservice.security.JwtFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Deny-by-default security for payment-service. Until this config landed there
 * was NO Spring Security filter chain at all — {@code /payments},
 * {@code /payments/shop-checkout} and {@code /actuator/prometheus} were
 * reachable by anyone, and the only "auth" was the inline
 * {@code jwtUtil.isTokenValid} check buried in {@link
 * innbucks.paymentservice.controller.TransfersController}. That left the
 * shop-checkout endpoint open to drain any customer's loyalty points (the
 * caller-supplied {@code msisdn} was the only thing identifying the wallet).
 *
 * <p>Now: {@link JwtFilter} parses the bearer JWT and pins the customer's
 * {@code phoneNumber} claim into Spring's {@code SecurityContext}; every
 * non-public path requires that authentication; controllers derive the
 * caller's MSISDN from the principal rather than the request body.
 *
 * <p>Anonymous paths are deliberately narrow:
 * <ul>
 *   <li>{@code /actuator/health}, {@code /actuator/info} — orchestrator probes
 *   <li>{@code /swagger-ui/**}, {@code /v3/api-docs/**} — API explorer
 *   <li>{@code OPTIONS /**} — CORS preflight
 *   <li>{@code /error} — Spring's error dispatcher target
 * </ul>
 * Everything else (including {@code /actuator/prometheus}) requires a valid
 * customer JWT.
 */
@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtFilter jwtFilter;

    // CORS lives exclusively on the api-gateway (globalcors + RemoveResponseHeader
    // filters per PR #182). Browsers only ever talk to the gateway, so a per-service
    // CORS filter here would emit a second set of headers that collide with the
    // gateway's and trip its DefaultCorsProcessor with "Invalid CORS request".
    // Don't re-introduce a service-level CORS config without also un-doing the
    // gateway-side strip.

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(
                                "/actuator/health",
                                "/actuator/health/**",
                                "/actuator/info",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/error"
                        ).permitAll()
                        // Everything else — /payments/**, /actuator/prometheus,
                        // any future endpoint — requires a valid customer JWT
                        // populated by JwtFilter.
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
                                    "{\"code\":\"403 FORBIDDEN\",\"message\":\"Forbidden\",\"data\":null}"
                            );
                        })
                )
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
