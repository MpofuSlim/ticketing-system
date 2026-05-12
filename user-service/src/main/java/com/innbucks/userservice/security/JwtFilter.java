package com.innbucks.userservice.security;

import com.innbucks.userservice.service.TokenRevocationService;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Lazy;
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
    @Lazy
    private final TokenRevocationService tokenRevocationService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
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
                // Tampered signature, expired, or malformed. Reject immediately
                // so the client can distinguish "refresh me" from "you forgot a
                // token" instead of slipping through unauthenticated and hitting
                // a generic 401 downstream.
                writeUnauthorized(response, "INVALID_TOKEN", "Token is invalid or expired");
                return;
            }
            if (tokenRevocationService.isRevoked(token)) {
                writeUnauthorized(response, "TOKEN_REVOKED", "Token has been revoked");
                return;
            }

            String email = jwtUtil.extractEmail(token);
            List<String> roles = jwtUtil.extractRoles(token);
            List<String> services = jwtUtil.extractServices(token);
            Integer tier = jwtUtil.extractTier(token);
            Boolean verified = jwtUtil.extractVerified(token);

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
        } catch (Exception e) {
            // Defensive: if claim extraction blows up for any reason (corrupt
            // payload, etc.) treat it the same as an invalid token rather than
            // letting the request leak through unauthenticated.
            log.warn("JWT validation error path={} message={}", request.getRequestURI(), e.getMessage());
            writeUnauthorized(response, "INVALID_TOKEN", "Token is invalid or expired");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void writeUnauthorized(HttpServletResponse response, String code, String message) throws IOException {
        SecurityContextHolder.clearContext();
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"data\":null}"
        );
    }

    private static final List<String> EXCLUDED_PATHS = List.of(
            "/swagger-ui",
            "/v3/api-docs",
            "/auth",
            "/error"
    );

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return EXCLUDED_PATHS.stream().anyMatch(path::startsWith);
    }
}
