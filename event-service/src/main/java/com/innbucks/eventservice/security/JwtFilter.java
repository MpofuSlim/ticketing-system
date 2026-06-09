package com.innbucks.eventservice.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;

    // Request attribute the country claim is stashed under so controllers can
    // read it via @RequestAttribute without re-parsing the token. Only set
    // when a valid Bearer token carrying a `country` claim is present.
    public static final String COUNTRY_ATTRIBUTE = "jwtCountry";

    /** MDC key for the customer's home-country routing tag, sourced from
     *  the {@code homeCountry} JWT claim. Distinct from {@code
     *  CountryMdcConfig.MDC_KEY} ("country"), which is the DEPLOYMENT pin. */
    public static final String HOME_COUNTRY_MDC_KEY = "homeCountry";

    private static final List<String> EXCLUDED_PATHS = List.of(
            "/swagger-ui",
            "/v3/api-docs",
            "/error"
    );

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return EXCLUDED_PATHS.stream().anyMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        // No Authorization header — let the chain proceed; SecurityConfig
        // decides whether the path needs auth.
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(7);
        try {
            if (!jwtUtil.isTokenValid(token)) {
                // Reject present-but-invalid tokens immediately so clients can
                // distinguish "refresh me" from "you forgot a token" instead of
                // slipping through unauthenticated and hitting a downstream
                // generic 401 with the same message.
                writeUnauthorized(response, "INVALID_TOKEN", "Token is invalid or expired");
                return;
            }

            String email = jwtUtil.extractEmail(token);
            List<String> roles = jwtUtil.extractRoles(token);
            List<String> services = jwtUtil.extractServices(token);
            Integer tier = jwtUtil.extractTier(token);
            Boolean verified = jwtUtil.extractVerified(token);
            String country = jwtUtil.extractCountry(token);
            if (country != null && !country.isBlank()) {
                request.setAttribute(COUNTRY_ATTRIBUTE, country);
            }

            List<SimpleGrantedAuthority> authorities = new ArrayList<>();
            for (String role : roles) {
                if (role != null && !role.isBlank()) {
                    authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
                }
            }
            for (String service : services) {
                if (service != null && !service.isBlank()) {
                    authorities.add(new SimpleGrantedAuthority("SERVICE_" + service.toUpperCase()));
                }
            }
            if (tier != null) {
                for (int i = 1; i <= tier && i <= 4; i++) {
                    authorities.add(new SimpleGrantedAuthority("TIER_" + i));
                }
            }
            if (Boolean.TRUE.equals(verified)) {
                authorities.add(new SimpleGrantedAuthority("VERIFIED"));
            }

            var auth = new UsernamePasswordAuthenticationToken(email, null, authorities);
            SecurityContextHolder.getContext().setAuthentication(auth);
            log.debug("JWT authenticated subject={} roles={} services={} tier={} path={}",
                    email, roles, services, tier, request.getRequestURI());
        } catch (Exception e) {
            log.warn("JWT validation error path={} message={}", request.getRequestURI(), e.getMessage());
            writeUnauthorized(response, "INVALID_TOKEN", "Token is invalid or expired");
            return;
        }

        // Push the customer's homeCountry into MDC for the downstream chain.
        // JwtUtil.extractHomeCountry returns null on any failure / missing
        // claim (legacy tokens, staff tokens), so we just skip the put in
        // those cases. Cleared in finally so request-thread recycling doesn't
        // leak it into the next request.
        String homeCountry = jwtUtil.extractHomeCountry(token);
        boolean mdcSet = false;
        if (homeCountry != null && !homeCountry.isBlank()) {
            MDC.put(HOME_COUNTRY_MDC_KEY, homeCountry);
            mdcSet = true;
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (mdcSet) {
                MDC.remove(HOME_COUNTRY_MDC_KEY);
            }
        }
    }

    private void writeUnauthorized(HttpServletResponse response, String code, String message) throws IOException {
        SecurityContextHolder.clearContext();
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"data\":null}"
        );
    }
}
