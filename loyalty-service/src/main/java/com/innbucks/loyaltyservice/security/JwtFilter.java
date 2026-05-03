package com.innbucks.loyaltyservice.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;

    private static final List<String> EXCLUDED_PATHS = List.of(
            "/swagger-ui",
            "/v3/api-docs",
            "/error",
            "/h2-console"
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
                    String role = jwtUtil.extractRole(token);
                    var auth = new UsernamePasswordAuthenticationToken(
                            email, null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + role)));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                    log.debug("JWT authenticated subject={} role={} path={}",
                            email, role, request.getRequestURI());
                }
            } catch (Exception e) {
                log.warn("JWT validation error path={} message={}", request.getRequestURI(), e.getMessage());
            }
        }
        filterChain.doFilter(request, response);
    }
}
