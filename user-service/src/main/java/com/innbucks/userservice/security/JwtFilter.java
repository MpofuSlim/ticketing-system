package com.innbucks.userservice.security;

import com.innbucks.userservice.cells.CellAffinityChecker;
import com.innbucks.userservice.cells.WrongCellException;
import com.innbucks.userservice.service.TokenRevocationService;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.slf4j.MDC;
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

    /** MDC key for the customer's home-country routing tag, sourced from
     *  the {@code homeCountry} JWT claim minted by JwtUtil from the user's
     *  MSISDN. Distinct from {@code CountryMdcConfig.MDC_KEY} ("country"),
     *  which is the DEPLOYMENT pin — the two together let us spot
     *  wrong-cell requests once edge routing lands. */
    public static final String HOME_COUNTRY_MDC_KEY = "homeCountry";

    private final JwtUtil jwtUtil;
    @Lazy
    private final TokenRevocationService tokenRevocationService;
    private final CellAffinityChecker cellAffinityChecker;

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

            // Single-active-session gate. Each /auth/login bumps the user's
            // token_version column; a token whose claim is stale belongs to
            // a session that was superseded by a later login (potentially
            // on another device) and must be rejected. SESSION_SUPERSEDED
            // is a distinct error code from INVALID_TOKEN / TOKEN_REVOKED so
            // the FE can decide whether to redirect to login vs offer a
            // refresh.
            long claimedVersion = jwtUtil.extractTokenVersion(token);
            if (!tokenRevocationService.isTokenVersionCurrent(email, claimedVersion)) {
                writeUnauthorized(response, "SESSION_SUPERSEDED",
                        "This session has been ended by a newer login");
                return;
            }
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

        // Push the customer's homeCountry into MDC for the lifetime of the
        // downstream chain so every log line emitted by controllers /
        // services downstream of this filter carries the customer's
        // country alongside CorrelationIdFilter's correlationId and
        // CountryMdcConfig's deployment country. Cleared in finally so a
        // recycled request thread doesn't leak it into the next request.
        // Claim may be absent (legacy tokens minted before step 1, staff
        // tokens with no MSISDN, or customers whose phone prefix isn't a
        // known InnBucks market) — we just skip the MDC put in that case
        // rather than fabricate a value.
        String homeCountry = safeExtractHomeCountry(token);

        // Step 7 — wrong-cell defence in depth. A JWT minted by another cell
        // that somehow reached this one (misrouted client, stale base URL) is
        // rejected with 409 wrong_cell + homeBaseUrl so the FE can switch and
        // retry. Local-cell JWTs (or legacy tokens with no homeCountry claim)
        // pass through unchanged. Auth has already succeeded at this point —
        // we are NOT trusting the claim to grant access, only using it to
        // surface a routing error instead of letting the request hit a stranger
        // customer's cell.
        try {
            cellAffinityChecker.requireDomesticCountry(homeCountry);
        } catch (WrongCellException ex) {
            writeWrongCell(response, ex);
            return;
        }

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

    private String safeExtractHomeCountry(String token) {
        try {
            return jwtUtil.extractHomeCountry(token);
        } catch (Exception e) {
            // Tokens that pass isTokenValid above should never fail claim
            // extraction, but if they do we'd rather lose the MDC tag than
            // 500 the request — auth already succeeded, downstream should
            // proceed without it.
            return null;
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

    /**
     * 409 wrong_cell envelope mirroring the {@code GlobalExceptionHandler}
     * payload for the MSISDN affinity path. Both surfaces emit the same JSON
     * shape so the FE has one branch to handle. {@code homeBaseUrl} is JSON
     * null when {@link CellRegistry} doesn't know the home cell's URL yet —
     * the FE then falls back to {@code GET /cells/lookup}.
     */
    private void writeWrongCell(HttpServletResponse response, WrongCellException ex) throws IOException {
        SecurityContextHolder.clearContext();
        response.setStatus(HttpServletResponse.SC_CONFLICT);
        response.setContentType("application/json");
        String homeUrlJson = ex.getHomeBaseUrl() == null
                ? "null"
                : "\"" + ex.getHomeBaseUrl().replace("\"", "\\\"") + "\"";
        response.getWriter().write(
                "{\"code\":\"409 CONFLICT\",\"message\":\"" + ex.getMessage().replace("\"", "\\\"")
                        + "\",\"data\":{\"errorCode\":\"wrong_cell\",\"homeCountry\":\""
                        + ex.getHomeCountry() + "\",\"homeBaseUrl\":" + homeUrlJson + "}}"
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
        // send-money/details returns a recipient's deposit-account identifiers and
        // MUST be authenticated (the sender is logged in), so it cannot ride the
        // blanket /auth skip — process the JWT here so SecurityConfig can enforce
        // .authenticated() on it (audit H1).
        if (path.startsWith("/auth/customer/send-money/details")) {
            return false;
        }
        return EXCLUDED_PATHS.stream().anyMatch(path::startsWith);
    }
}
