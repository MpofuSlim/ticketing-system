package com.innbucks.userservice.controller;

import com.innbucks.userservice.service.AuditContext;
import com.innbucks.userservice.service.AuditEventType;
import com.innbucks.userservice.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single source of truth for the X-Internal-Token check that every
 * {@code /users/internal/**} controller shares — and the one place that
 * persists an {@link AuditEventType#AUTH_INTERNAL_TOKEN_FAILURE} row when the
 * check fails.
 *
 * <p>Three internal controllers previously duplicated this logic and emitted
 * only a {@code log.warn} for one of the failure modes (server-side token
 * unset). The rest were SILENT 401s — which hid both prod misconfiguration AND
 * active probing of the platform's S2S trust boundary. This helper closes that
 * audit gap by recording every rejection with a distinct {@code failure_reason}:
 *
 * <ul>
 *   <li>{@code token_not_configured} — server's expected token is blank
 *       (deploy-time misconfig; rejects ALL traffic with the same reason).</li>
 *   <li>{@code token_missing} — caller sent no {@code X-Internal-Token}
 *       header.</li>
 *   <li>{@code token_mismatch} — header present but didn't match (constant-time
 *       compare). The active-probing signal.</li>
 * </ul>
 *
 * <p>{@code metadata} carries {@code path} + {@code presentedTokenLength} —
 * NEVER the token itself (per AuditService's "no raw secrets" contract).
 *
 * <p>The audit write runs in REQUIRES_NEW + swallows exceptions inside
 * {@code AuditService.record}, so a broken audit path never escalates an
 * already-401 request into a 500.
 */
@Component
@Slf4j
public class InternalTokenAuthorizer {

    private final String expectedToken;
    private final AuditService auditService;

    public InternalTokenAuthorizer(
            @Value("${innbucks.internal-api-token:}") String expectedToken,
            AuditService auditService) {
        this.expectedToken = expectedToken;
        this.auditService = auditService;
    }

    /**
     * Constant-time check the shared X-Internal-Token. On failure persists an
     * AUTH_INTERNAL_TOKEN_FAILURE audit row and returns false; the controller
     * then returns 401 unchanged. Caller passes the live request so we can
     * stamp IP / user-agent / path on the audit row.
     */
    public boolean authorized(String presented, HttpServletRequest request) {
        if (expectedToken == null || expectedToken.isBlank()) {
            log.warn("Internal API token is not configured; rejecting call");
            recordFailure("token_not_configured", presented, request);
            return false;
        }
        if (presented == null) {
            recordFailure("token_missing", null, request);
            return false;
        }
        if (!MessageDigest.isEqual(
                expectedToken.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8))) {
            recordFailure("token_mismatch", presented, request);
            return false;
        }
        return true;
    }

    private void recordFailure(String reason, String presented, HttpServletRequest request) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("path", request == null ? null : request.getRequestURI());
        // Length only — never the token. Useful for distinguishing a malformed
        // empty value ("") from a real probing attempt (32+ chars).
        metadata.put("presentedTokenLength", presented == null ? 0 : presented.length());
        AuditContext context = request == null
                ? AuditContext.none()
                : new AuditContext(clientIp(request), request.getHeader("User-Agent"));
        // actorType=ANONYMOUS: no JWT, no user identity. actorId stays null —
        // an unauthenticated caller has nothing to attribute the attempt to
        // beyond IP / correlationId, which already live on the row.
        auditService.recordFailure(
                AuditEventType.AUTH_INTERNAL_TOKEN_FAILURE,
                null, AuditService.ACTOR_TYPE_ANONYMOUS,
                null, null,
                reason, metadata, context);
    }

    /** Same X-Forwarded-For-aware extraction as AdminUserController / AuthController. */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            String first = (comma < 0 ? forwarded : forwarded.substring(0, comma)).trim();
            if (!first.isEmpty()) return first;
        }
        return request.getRemoteAddr();
    }
}
