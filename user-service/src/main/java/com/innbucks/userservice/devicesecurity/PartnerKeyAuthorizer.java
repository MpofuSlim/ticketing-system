package com.innbucks.userservice.devicesecurity;

import com.innbucks.userservice.service.AuditContext;
import com.innbucks.userservice.service.AuditEventType;
import com.innbucks.userservice.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The {@code x-api-key} check for DTX's two external callers: the broker (every
 * app-facing call arrives through it, plus its own login-result and ticket calls)
 * and the *569# USSD service. Neither is a fleet service, so neither gets the
 * fleet's {@code X-Internal-Token}; each has its own key, so a leak of one
 * cannot drive the other's endpoints.
 *
 * <p>Constant-time compare. A blank configured key refuses everything (fail
 * closed). Every refusal is a 401 the controller check produced — never a Spring
 * Security 401 — and lands on the audit chain with the path and the presented
 * key's LENGTH only, like {@code InternalTokenAuthorizer}.
 */
@Component
@Slf4j
public class PartnerKeyAuthorizer {

    public static final String HEADER = "x-api-key";

    public enum Partner { BROKER, USSD }

    private final DeviceSecurityProperties properties;
    private final AuditService auditService;
    private final DeviceSecurityMetrics metrics;

    public PartnerKeyAuthorizer(DeviceSecurityProperties properties, AuditService auditService,
                                DeviceSecurityMetrics metrics) {
        this.properties = properties;
        this.auditService = auditService;
        this.metrics = metrics;
    }

    /** Throws the 401 unless the request carries this partner's key. */
    public void require(Partner partner, HttpServletRequest request) {
        String expected = partner == Partner.BROKER ? properties.getBrokerApiKey() : properties.getUssdApiKey();
        String presented = request == null ? null : request.getHeader(HEADER);
        String reason = null;
        if (expected == null || expected.isBlank()) {
            reason = "key_not_configured";
            log.warn("Device-security {} key is not configured; refusing {}", partner,
                    request == null ? "" : request.getRequestURI());
        } else if (presented == null || presented.isBlank()) {
            reason = "key_missing";
        } else if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8))) {
            reason = "key_mismatch";
        }
        if (reason == null) return;

        metrics.partnerKeyFailure(partner.name(), reason);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("partner", partner.name());
        metadata.put("path", request == null ? null : request.getRequestURI());
        metadata.put("presentedKeyLength", presented == null ? 0 : presented.length());
        auditService.recordFailure(AuditEventType.DEVICE_SECURITY_PARTNER_KEY_FAILURE, null,
                AuditService.ACTOR_TYPE_ANONYMOUS, null, null, reason, metadata,
                request == null ? AuditContext.none()
                        : new AuditContext(DeviceSecurityRequests.clientIp(request), request.getHeader("User-Agent")));
        throw new DeviceSecurityException(HttpStatus.UNAUTHORIZED, "invalid_api_key", "Invalid or missing API key.");
    }
}
