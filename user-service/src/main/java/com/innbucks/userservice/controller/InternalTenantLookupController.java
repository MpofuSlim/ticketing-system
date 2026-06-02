package com.innbucks.userservice.controller;

import com.innbucks.userservice.dto.ApiResult;
import com.innbucks.userservice.dto.TenantLookupDTO;
import com.innbucks.userservice.entity.TenantProfile;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.TenantProfileRepository;
import com.innbucks.userservice.repository.UserRepository;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service-to-service lookup consumed by event-service to attach an event's
 * owning organizer's business details (name / email / address) to event
 * responses. Gated by the shared {@code X-Internal-Token} header rather than a
 * user JWT — the caller is another backend, not a logged-in user.
 *
 * <p>Class-level {@link Hidden} keeps this out of the public Swagger UI; the
 * gateway additionally blocks {@code /users/internal/**} at the edge via the
 * {@code user-internal-deny} route, mirroring {@code /loyalty/internal/**}.
 */
@RestController
@RequestMapping("/users/internal")
@Slf4j
@Hidden
public class InternalTenantLookupController {

    private final UserRepository userRepository;
    private final TenantProfileRepository tenantProfileRepository;
    private final String expectedToken;

    public InternalTenantLookupController(UserRepository userRepository,
                                          TenantProfileRepository tenantProfileRepository,
                                          @Value("${innbucks.internal-api-token:}") String expectedToken) {
        this.userRepository = userRepository;
        this.tenantProfileRepository = tenantProfileRepository;
        this.expectedToken = expectedToken;
    }

    @PostMapping("/tenants/lookup")
    @Operation(summary = "(S2S) Resolve organizer business details by tenant id",
            description = "Given a list of tenantIds (account emails), returns each one's business " +
                          "name, email and address. Tenants with no business profile are simply " +
                          "absent from the result. Used by event-service to enrich event responses.")
    public ResponseEntity<?> lookup(@RequestHeader(value = "X-Internal-Token", required = false) String token,
                                    @RequestBody LookupRequest request) {
        if (!authorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        List<String> tenantIds = request == null ? null : request.tenantIds();
        if (tenantIds == null || tenantIds.isEmpty()) {
            return ResponseEntity.ok(ApiResult.ok("No tenants requested", Collections.emptyList()));
        }

        // tenantId == account email (the JWT subject). Resolve users, then their
        // tenant profiles in one batch each (no N+1).
        List<User> users = userRepository.findByEmailIn(tenantIds);
        if (users.isEmpty()) {
            return ResponseEntity.ok(ApiResult.ok("Tenants resolved", Collections.emptyList()));
        }
        Map<Long, String> emailByUserId = users.stream()
                .collect(Collectors.toMap(User::getId, User::getEmail));

        List<TenantProfile> profiles = tenantProfileRepository.findByUserIdIn(emailByUserId.keySet());
        List<TenantLookupDTO> body = profiles.stream()
                .map(p -> TenantLookupDTO.builder()
                        .tenantId(emailByUserId.get(p.getUser().getId()))
                        .businessName(p.getBusinessName())
                        .email(p.getBusinessEmail())
                        .address(p.getBusinessAddress())
                        .build())
                .filter(dto -> dto.getTenantId() != null)
                .collect(Collectors.toList());

        log.debug("Internal tenant lookup requested={} resolved={}", tenantIds.size(), body.size());
        return ResponseEntity.ok(ApiResult.ok("Tenants resolved", body));
    }

    /** Request body: {@code {"tenantIds": ["alice@x.co", "bob@y.co"]}}. */
    public record LookupRequest(List<String> tenantIds) {}

    private boolean authorized(String presented) {
        if (expectedToken == null || expectedToken.isBlank()) {
            // No token configured: refuse all internal calls rather than fail-open.
            log.warn("Internal API token is not configured; rejecting call");
            return false;
        }
        if (presented == null) {
            return false;
        }
        // Constant-time compare to avoid leaking the secret via a timing oracle.
        return MessageDigest.isEqual(
                expectedToken.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }
}
