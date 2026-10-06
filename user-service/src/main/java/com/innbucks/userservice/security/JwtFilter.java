package com.innbucks.userservice.security;

import com.innbucks.userservice.cells.CellAffinityChecker;
import com.innbucks.userservice.cells.WrongCellException;
import com.innbucks.userservice.service.TokenRevocationService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
    /**
     * Back-fills permissions for a token minted before the {@code perms} claim
     * existed — the rolling-deploy bridge, see {@code permissionsFor}.
     */
    private final PermissionResolver permissionResolver;
    @Lazy
    private final TokenRevocationService tokenRevocationService;
    private final CellAffinityChecker cellAffinityChecker;

    /**
     * The permissions to authorize this request with.
     *
     * <p>Normally the token's own {@code perms} claim (V35). When that claim is
     * ABSENT the permissions are re-derived from the token's roles instead.
     *
     * <p>That fallback is what makes migrating a check from {@code hasRole} to
     * {@code hasAuthority} safe to deploy. A token minted before this release
     * carries roles but no {@code perms}, so without it every already-logged-in
     * admin would get a 403 from every migrated endpoint until their token
     * expired or they logged in again — a self-inflicted outage on the admin
     * surface, visible only during the rollout and therefore easy to ship
     * unnoticed. (The CI run on this PR's first commit caught exactly this: eight
     * AdminUserControllerTest cases 403'd because their caller held
     * ROLE_SUPER_ADMIN and nothing else, which is precisely the shape of an
     * in-flight token.)
     *
     * <p>It grants no more than the claim would: the same
     * {@link PermissionResolver} against the same {@code roles} table, so it can
     * only ever produce what a freshly minted token for that account would
     * carry. The extra query costs one small indexed read, and only for tokens
     * predating the claim — it stops happening on its own as sessions turn over.
     */
    private List<String> permissionsFor(Claims claims, List<String> roles) {
        return authorityFor(claims, null, roles).permissions();
    }

    /**
     * The mint-time eligibility filter (V44), applied on the one path here that
     * mints nothing: a token WITHOUT a {@code perms} claim, whose permissions are
     * re-derived from its roles on every request. Without the filter, a grant to
     * such a token's role would reach its holder with no mint at all — so an
     * ineligible holder is counted (watch) or loses the PLATFORM codes and NAMED
     * staff roles (enforce) here exactly as at {@code AuthService.buildResponse}.
     * Optional so the unit tests that construct this filter need neither.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.innbucks.userservice.service.StaffMintFilter staffMintFilter;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.innbucks.userservice.repository.UserRepository userRepository;

    /** Roles and permissions to authorize with — see {@link #permissionsFor}. */
    private com.innbucks.userservice.service.StaffMintFilter.Minted authorityFor(Claims claims, String subject,
                                                                               List<String> roles) {
        List<String> claimed = jwtUtil.extractPermissions(claims);
        if (!claimed.isEmpty() || roles.isEmpty() || permissionResolver == null) {
            return new com.innbucks.userservice.service.StaffMintFilter.Minted(roles, claimed);
        }
        List<String> resolved = new ArrayList<>(permissionResolver.resolve(roles));
        // Only a token carrying staff authority (a NAMED role name or a PLATFORM
        // code) can be affected by the filter — so only then is the account read.
        // A CUSTOMER / TEAM_MEMBER / business token costs no extra query, and
        // never reaches the ineligible-holder metric.
        if (staffMintFilter == null || userRepository == null || subject == null
                || !StaffRoles.carriesStaffAuthority(roles, resolved)) {
            return new com.innbucks.userservice.service.StaffMintFilter.Minted(roles, resolved);
        }
        com.innbucks.userservice.entity.User account = (subject.contains("@")
                ? userRepository.findByEmail(subject)
                : userRepository.findByPhoneNumber(subject)).orElse(null);
        return staffMintFilter.apply(account, roles, resolved);
    }

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
        // Verified ONCE per request (signature under the alg-selected key, iss,
        // aud, expiry); every claim below is read from this one result instead
        // of re-parsing and re-verifying the token for each claim.
        Claims claims;
        try {
            try {
                claims = jwtUtil.parseClaims(token);
            } catch (JwtException e) {
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

            String email = jwtUtil.extractEmail(claims);

            // Single-active-session gate. Each /auth/login bumps the user's
            // token_version column; a token whose claim is stale belongs to
            // a session that was superseded by a later login (potentially
            // on another device) and must be rejected. SESSION_SUPERSEDED
            // is a distinct error code from INVALID_TOKEN / TOKEN_REVOKED so
            // the FE can decide whether to redirect to login vs offer a
            // refresh.
            //
            // The same single read also carries users.active, so a DEACTIVATED
            // account is refused on its very next request — not merely once the
            // access token expires. ACCOUNT_DEACTIVATED is its own code so the FE
            // can say why instead of offering a refresh that will also be refused.
            long claimedVersion = jwtUtil.extractTokenVersion(claims);
            TokenRevocationService.SessionState session =
                    tokenRevocationService.sessionState(email, claimedVersion);
            if (session == TokenRevocationService.SessionState.INACTIVE) {
                writeUnauthorized(response, "ACCOUNT_DEACTIVATED",
                        "This account has been deactivated");
                return;
            }
            if (session != TokenRevocationService.SessionState.CURRENT) {
                writeUnauthorized(response, "SESSION_SUPERSEDED",
                        "This session has been ended by a newer login");
                return;
            }
            // mustChangePassword gate: a JWT minted for a freshly-onboarded /
            // freshly-reset user carries this claim. Only the /auth/** paths
            // (excluded from this filter via shouldNotFilter) can be reached
            // until the password is rotated — every other endpoint here is
            // blocked with a typed error code the FE branches on to redirect
            // to the change-password screen. AuthService bumps token_version
            // on successful change, so the claim-carrying JWT is invalid
            // immediately afterward and the next login mints a fresh token.
            if (jwtUtil.extractMustChangePassword(claims)) {
                writePasswordChangeRequired(response);
                return;
            }
            com.innbucks.userservice.service.StaffMintFilter.Minted authority =
                    authorityFor(claims, email, jwtUtil.extractRoles(claims));
            List<String> roles = authority.roles();
            List<String> permissions = authority.permissions();
            List<String> services = jwtUtil.extractServices(claims);
            Integer tier = jwtUtil.extractTier(claims);
            Boolean verified = jwtUtil.extractVerified(claims);

            List<SimpleGrantedAuthority> authorities = new ArrayList<>();
            for (String role : roles) {
                if (role != null && !role.isBlank()) {
                    authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
                }
            }
            // Permissions (V35) are granted as BARE authorities — no prefix — so
            // a check reads hasAuthority('users:read'). Roles keep their ROLE_
            // prefix because hasRole() adds it implicitly.
            //
            // Both are granted, deliberately: the migration from hasRole to
            // hasAuthority is running one service at a time, so a token has to
            // satisfy whichever style each check still uses. The colon in a
            // permission code also makes the two namespaces impossible to
            // confuse — no role name can produce 'users:read', since role names
            // are UPPER_SNAKE_CASE (RoleAdminService.VALID_NAME).
            for (String permission : permissions) {
                if (permission != null && !permission.isBlank()) {
                    authorities.add(new SimpleGrantedAuthority(permission));
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
            // Stash the JWT's UUID claims on the authentication's details map
            // so downstream controllers / services can read them without
            // re-parsing the token (and without us having to swap the
            // principal type, which would break every existing `auth.getName()`
            // call that expects the email). Read via
            // {@link AuthenticatedCaller#userUuid(Authentication)}.
            UUID userUuid = jwtUtil.extractUserUuid(claims);
            UUID organizerUuid = jwtUtil.extractOrganizerUuid(claims);
            UUID organizationId = jwtUtil.extractOrganizationId(claims);
            if (userUuid != null || organizerUuid != null || organizationId != null) {
                Map<String, Object> details = new LinkedHashMap<>();
                if (userUuid != null) details.put(AuthDetailsKeys.USER_UUID, userUuid);
                if (organizerUuid != null) details.put(AuthDetailsKeys.ORGANIZER_UUID, organizerUuid);
                if (organizationId != null) details.put(AuthDetailsKeys.ORGANIZATION_ID, organizationId);
                auth.setDetails(details);
            }
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
        String homeCountry = safeExtractHomeCountry(claims);

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

    private String safeExtractHomeCountry(Claims claims) {
        try {
            return jwtUtil.extractHomeCountry(claims);
        } catch (Exception e) {
            // Claims that verified above should never fail claim
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
     * 403 envelope for a JWT that carries {@code mustChangePassword: true}
     * hitting a non-auth path. The FE branches on the {@code errorCode}
     * ({@code password_change_required}) to redirect to the change-password
     * screen rather than the generic 403 / 401 handler.
     */
    private void writePasswordChangeRequired(HttpServletResponse response) throws IOException {
        SecurityContextHolder.clearContext();
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"code\":\"403 FORBIDDEN\",\"message\":\"Password change required before " +
                "this account can use the rest of the app\",\"data\":{\"errorCode\":\"password_change_required\"}}"
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
            // DTX partner endpoints (broker, *569#) authenticate with an x-api-key,
            // never a fleet JWT. Filtered, a stray Authorization header the broker
            // forwarded would be rejected INVALID_TOKEN before the key is read.
            "/device-security/",
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
        // DTX device security's customer surface (Your devices, the in-session
        // step-up) is authenticated by the fleet CUSTOMER session and gated by
        // @PreAuthorize("hasRole('CUSTOMER')"). Riding the blanket /auth skip,
        // the token would never be read and every call would 401 as anonymous —
        // so, like send-money above, these paths are filtered.
        if (path.equals("/auth/devices") || path.startsWith("/auth/devices/") || path.startsWith("/auth/device/")) {
            return false;
        }
        return EXCLUDED_PATHS.stream().anyMatch(path::startsWith);
    }
}
