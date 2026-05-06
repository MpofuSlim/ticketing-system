package com.innbucks.bookingservice.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            try {
                if (jwtUtil.isTokenValid(token)) {
                    String email = jwtUtil.extractEmail(token);
                    List<String> roles = jwtUtil.extractRoles(token);
                    List<String> services = jwtUtil.extractServices(token);
                    Integer tier = jwtUtil.extractTier(token);
                    Boolean verified = jwtUtil.extractVerified(token);
                    String phoneNumber = jwtUtil.extractPhoneNumber(token);

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
                    auth.setDetails(new JwtAuthDetails(email, phoneNumber));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                    log.debug("JWT authenticated subject={} roles={} services={} tier={} path={}", email, roles, services, tier, request.getRequestURI());
                }
            } catch (Exception e) {
                log.warn("JWT validation error path={} message={}", request.getRequestURI(), e.getMessage());
            }
        }

        filterChain.doFilter(request, response);
    }
}

