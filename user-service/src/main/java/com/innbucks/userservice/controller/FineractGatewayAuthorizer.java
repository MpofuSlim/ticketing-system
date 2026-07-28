package com.innbucks.userservice.controller;

import com.innbucks.userservice.config.FineractGatewayProperties;
import com.innbucks.userservice.service.AuditContext;
import com.innbucks.userservice.service.AuditEventType;
import com.innbucks.userservice.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single source of truth for authenticating calls on the Fineract Message
 * Gateway facade ({@code /fineract-gateway/**}). Fineract's
 * {@code SmsConfigUtils} stamps two headers on every gateway call:
 * {@code Fineract-Platform-TenantId} and {@code Fineract-Tenant-App-Key};
 * the app key is the shared secret (constant-time compare) and the tenant id
 * must match the single tenant this facade serves.
 *
 * <p>Mirrors {@link InternalTokenAuthorizer} — same fail-closed order, same
 * audited-401 posture (silent 401s hide both prod misconfiguration and
 * probing of an S2S trust boundary), same "length only, never the secret"
 * metadata rule — but kept separate because the caller, header names and
 * secret custody differ.
 */
@Component
@Slf4j
public class FineractGatewayAuthorizer {

    public static final String TENANT_HEADER = "Fineract-Platform-TenantId";
    public static final String APP_KEY_HEADER = "Fineract-Tenant-App-Key";

    private final FineractGatewayProperties properties;
    private final AuditService auditService;

    // Mirrors InternalTokenAuthorizer: field-injected so unit tests that
    // construct the authorizer directly don't have to widen. Null => the
    // metric emit is skipped.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.innbucks.userservice.config.SecurityMetrics securityMetrics;

    public FineractGatewayAuthorizer(FineractGatewayProperties properties, AuditService auditService) {
        this.properties = properties;
        this.auditService = auditService;
    }

    /**
     * Constant-time check of the Fineract app key + exact-match tenant check.
     * On failure persists an AUTH_FINERACT_GATEWAY_FAILURE audit row and
     * returns false; the controller then returns 401 unchanged.
     */
    public boolean authorized(String presentedKey, String presentedTenant, HttpServletRequest request) {
        String expected = properties.getTenantAppKey();
        if (expected == null || expected.isBlank()) {
            log.warn("Fineract gateway app key is not configured; rejecting call");
            recordFailure("key_not_configured", presentedKey, request);
            return false;
        }
        if (presentedKey == null) {
            recordFailure("key_missing", null, request);
            return false;
        }
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                presentedKey.getBytes(StandardCharsets.UTF_8))) {
            recordFailure("key_mismatch", presentedKey, request);
            return false;
        }
        // Key valid — now pin the tenant. A correct key with a foreign tenant
        // id is a misconfigured second Fineract cell, not an attack, but it
        // must still bounce: reports would leak another tenant's statuses.
        if (presentedTenant == null || !presentedTenant.equals(properties.getTenantId())) {
            recordFailure("tenant_mismatch", presentedKey, request);
            return false;
        }
        return true;
    }

    private void recordFailure(String reason, String presentedKey, HttpServletRequest request) {
        if (securityMetrics != null) securityMetrics.fineractGatewayAuthFailure(reason);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("path", request == null ? null : request.getRequestURI());
        // Length only — never the key (per AuditService's "no raw secrets" contract).
        metadata.put("presentedKeyLength", presentedKey == null ? 0 : presentedKey.length());
        AuditContext context = request == null
                ? AuditContext.none()
                : new AuditContext(clientIp(request), request.getHeader("User-Agent"));
        auditService.recordFailure(
                AuditEventType.AUTH_FINERACT_GATEWAY_FAILURE,
                null, AuditService.ACTOR_TYPE_ANONYMOUS,
                null, null,
                reason, metadata, context);
    }

    /** Same X-Forwarded-For-aware extraction as InternalTokenAuthorizer. */
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
