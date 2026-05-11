package com.innbucks.loyaltyservice.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

/**
 * Extra claims pulled from the authenticated JWT. Attached to the request's
 * Authentication as {@code details} by {@link JwtFilter}.
 *
 * <p>{@code merchantId} is set for MERCHANT_ADMIN principals whose user-service
 * profile is linked to a specific loyalty merchant; null for callers that act
 * tenant-wide (e.g. SUPER_ADMIN).
 */
public record CallerDetails(UUID merchantId) {

    public static UUID currentMerchantId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return null;
        Object details = auth.getDetails();
        if (details instanceof CallerDetails cd) return cd.merchantId();
        return null;
    }
}
