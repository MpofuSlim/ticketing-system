package innbucks.paymentservice.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Parses {@code Authorization: Bearer <jwt>} and populates Spring's
 * {@link org.springframework.security.core.context.SecurityContext} with an
 * {@link UsernamePasswordAuthenticationToken} whose principal is the JWT's
 * {@code phoneNumber} claim. Downstream controllers read
 * {@code authentication.getName()} for the caller's MSISDN — never trusting a
 * value supplied in the request body.
 *
 * <p>Excluded paths (Swagger, health, OPTIONS) are skipped via
 * {@link #shouldNotFilter}. For everything else: a missing token lets the
 * chain proceed (SecurityConfig decides whether to allow) and an invalid
 * token short-circuits with a 401 JSON body so the FE can distinguish "no
 * token sent" from "tampered/expired token."
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;

    private static final List<String> EXCLUDED_PATHS = List.of(
            "/swagger-ui",
            "/v3/api-docs",
            "/error",
            "/actuator/health",
            "/actuator/info"
    );

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        return EXCLUDED_PATHS.stream().anyMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
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
                writeUnauthorized(response, "INVALID_TOKEN", "Token is invalid or expired");
                return;
            }
            String phoneNumber = jwtUtil.extractPhoneNumber(token);
            if (phoneNumber == null) {
                // Token is signature-valid but missing the phoneNumber claim
                // (e.g. a staff token from MERCHANT_ADMIN). Reject — every
                // /payments/** path is customer-only.
                writeUnauthorized(response, "INVALID_TOKEN", "Token has no phoneNumber claim");
                return;
            }
            var auth = new UsernamePasswordAuthenticationToken(phoneNumber, null, List.of());
            SecurityContextHolder.getContext().setAuthentication(auth);
        } catch (Exception e) {
            // Defensive: any claim-extraction failure is treated as an invalid
            // token rather than letting the request slip through unauthenticated.
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
}
