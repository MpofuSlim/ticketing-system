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
                        // Confirmation lookup is public — agents scan at the gate
                        .requestMatchers("/bookings/confirmation/**").permitAll()
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
