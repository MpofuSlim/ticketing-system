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
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/swagger-ui/**").permitAll()
                        .requestMatchers("/swagger-ui.html").permitAll()
                        .requestMatchers("/v3/api-docs/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        // NOTE: /bookings/confirmation/** and /bookings/phone/** are
                        // deliberately NOT permitAll — both return another customer's
                        // full PII + the scannable ticket QR keyed on a low-entropy
                        // identifier, so they fall through to anyRequest().authenticated()
                        // and the controller owner-scopes each to the caller's JWT
                        // identity (OWASP A01 / BOLA fix). Do NOT re-add a permitAll here.
                        // Public booking lookup by id — same bearer-credential model as
                        // the hosted ticket-QR endpoint (UUID is the access token) but a
                        // TRIMMED, PII-free DTO. Distinct path from the authenticated
                        // GET /bookings/{id} above so the access model is explicit at the URL.
                        .requestMatchers(HttpMethod.GET, "/bookings/public/**").permitAll()
                        // Public ticket artifacts (QR PNG + HTML view page) linked
                        // from the confirmation email/WhatsApp — opened with no app
                        // session. Bearer-instrument model: the unguessable booking
                        // UUID is the access control; TicketRenderingService only
                        // renders CONFIRMED bookings. NOT under /bookings/internal/**,
                        // so it routes through the public gateway normally.
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/bookings/*/tickets/**").permitAll()
                        // Brand assets — public PNGs served from classpath:/static/brand/
                        // (e.g. /brand/innbucks-logo.png) for the FE's WhatsApp templates,
                        // HTML emails, etc. Same anonymous-public model as the ticket QR.
                        // SecurityFilterChain + the gateway brand-assets-route both
                        // permit this path; Spring's static-resource handler serves the
                        // file from the booking-service jar.
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/brand/**").permitAll()
                        // A01: the active-counts reads moved under
                        // /bookings/internal/** (GET permitAll below + X-Internal-Token
                        // check in the controller + gateway edge-deny) so they are no
                        // longer anonymously enumerable. Do NOT re-add a public
                        // permitAll for /bookings/active-counts here.
                        .requestMatchers(HttpMethod.POST, "/bookings").permitAll()
                        // Confirm is called by payment-service after a payment.
                        // No JWT is involved — payment-service authenticates via
                        // the shared `X-Internal-Token` checked inside
                        // BookingController#confirmBooking. We permit at the
                        // Spring layer so the controller can return a clean
                        // ApiResult body instead of Spring's opaque default 401.
                        // Path is under /bookings/internal/** so the gateway's
                        // booking-internal-deny rule also blocks any public
                        // attempt (the "three files agree" contract).
                        .requestMatchers(HttpMethod.PATCH, "/bookings/internal/*/confirm").permitAll()
                        // Internal-only: event-service triggers the attendee
                        // notification fan-out here. The controller enforces the
                        // shared X-Internal-Token; we permit at the Spring layer
                        // so it can return a clean 401 body. The gateway also
                        // denies /bookings/internal/** at the edge.
                        .requestMatchers(HttpMethod.POST, "/bookings/internal/**").permitAll()
                        // S2S read for payment-service (GET /bookings/internal/{id}) —
                        // X-Internal-Token checked in the controller, denied at the
                        // gateway edge. Without this permit anyRequest().authenticated()
                        // 401s the call before the controller's token check runs (the
                        // cause of "booking-service get failed status=401").
                        .requestMatchers(HttpMethod.GET, "/bookings/internal/**").permitAll()
                        // S2S hold extension before code mint (PATCH
                        // /bookings/internal/{id}/extend-hold) — same trio:
                        // controller token check + this permit + gateway deny.
                        .requestMatchers(HttpMethod.PATCH, "/bookings/internal/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
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
