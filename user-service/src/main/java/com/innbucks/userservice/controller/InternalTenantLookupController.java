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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Service-to-service lookup consumed by event-service to attach an event's
 * owning organizer's business details (businessName / businessAddress /
 * businessEmail) to event responses. Keyed by {@code userUuid} — the stable
 * cross-service organizer identifier (was email until the email-as-tenant-id
 * pattern was removed). Gated by the shared {@code X-Internal-Token} header
 * rather than a user JWT — the caller is another backend, not a logged-in user.
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
    private final InternalTokenAuthorizer tokenAuthorizer;

    public InternalTenantLookupController(UserRepository userRepository,
                                          TenantProfileRepository tenantProfileRepository,
                                          InternalTokenAuthorizer tokenAuthorizer) {
        this.userRepository = userRepository;
        this.tenantProfileRepository = tenantProfileRepository;
        this.tokenAuthorizer = tokenAuthorizer;
    }

    @PostMapping("/tenants/lookup-by-uuid")
    @Operation(summary = "(S2S) Resolve organizer business details by user_uuid",
            description = "Given a list of user_uuids, returns each organizer's business name, " +
                          "email and address. Organizers with no business profile are simply absent " +
                          "from the result. Used by event-service to enrich event responses.")
    public ResponseEntity<?> lookupByUuid(@RequestHeader(value = "X-Internal-Token", required = false) String token,
                                          @RequestBody UuidLookupRequest request,
                                          jakarta.servlet.http.HttpServletRequest httpRequest) {
        if (!tokenAuthorizer.authorized(token, httpRequest)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        List<UUID> userUuids = request == null ? null : request.userUuids();
        if (userUuids == null || userUuids.isEmpty()) {
            return ResponseEntity.ok(ApiResult.ok("No tenants requested", Collections.emptyList()));
        }

        List<User> users = userRepository.findByUserUuidIn(userUuids);
        if (users.isEmpty()) {
            return ResponseEntity.ok(ApiResult.ok("Tenants resolved", Collections.emptyList()));
        }
        // user.id is the local PK that TenantProfile FKs against; user_uuid is
        // the cross-service identifier we key the response on.
        Map<Long, UUID> uuidByUserId = users.stream()
                .collect(Collectors.toMap(User::getId, User::getUserUuid));

        List<TenantProfile> profiles = tenantProfileRepository.findByUserIdIn(uuidByUserId.keySet());
        List<TenantLookupDTO> body = profiles.stream()
                .map(p -> TenantLookupDTO.builder()
                        .userUuid(uuidByUserId.get(p.getUser().getId()))
                        .businessName(p.getBusinessName())
                        .businessAddress(p.getBusinessAddress())
                        .businessEmail(p.getBusinessEmail())
                        .build())
                .filter(dto -> dto.getUserUuid() != null)
                .collect(Collectors.toList());

        log.debug("Internal tenant lookup-by-uuid requested={} resolved={}", userUuids.size(), body.size());
        return ResponseEntity.ok(ApiResult.ok("Tenants resolved", body));
    }

    /** Request body: {@code {"userUuids": ["8b3a9c0e-...", "5fc4c9d2-..."]}}. */
    public record UuidLookupRequest(List<UUID> userUuids) {}
}
