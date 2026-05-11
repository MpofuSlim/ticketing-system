package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.repository.MerchantRepository;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Service-to-service endpoints consumed by other backends (today: user-service
 * at login). Gated by a shared-secret header rather than the user JWT — the
 * caller is another microservice, not a logged-in user. Hidden from Swagger.
 */
@RestController
@RequestMapping("/loyalty/internal/merchants")
@Slf4j
@Hidden
public class InternalMerchantLookupController {

    private final MerchantRepository merchants;
    private final String expectedToken;

    public InternalMerchantLookupController(MerchantRepository merchants,
                                            @Value("${innbucks.internal-api-token:}") String expectedToken) {
        this.merchants = merchants;
        this.expectedToken = expectedToken;
    }

    @GetMapping("/by-admin")
    public ResponseEntity<?> byAdminEmail(@RequestHeader(value = "X-Internal-Token", required = false) String token,
                                          @RequestParam("email") String email) {
        if (!authorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (email == null || email.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "email is required"));
        }
        Optional<Merchant> hit = merchants.findFirstByAdminEmailOrderByCreatedAtAsc(email);
        if (hit.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        UUID merchantId = hit.get().getId();
        log.debug("Internal lookup resolved adminEmail={} -> merchantId={}", email, merchantId);
        return ResponseEntity.ok(Map.of("merchantId", merchantId));
    }

    private boolean authorized(String presented) {
        if (expectedToken == null || expectedToken.isBlank()) {
            // No token configured: refuse all internal calls rather than fail-open.
            log.warn("Internal API token is not configured; rejecting call");
            return false;
        }
        return expectedToken.equals(presented);
    }
}
