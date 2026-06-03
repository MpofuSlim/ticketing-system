package com.innbucks.userservice.controller;

import com.innbucks.userservice.client.DepositAccount;
import com.innbucks.userservice.util.MsisdnMasking;
import com.innbucks.userservice.dto.*;
import com.innbucks.userservice.security.JwtUtil;
import com.innbucks.userservice.service.AuditContext;
import com.innbucks.userservice.service.AuditEventType;
import com.innbucks.userservice.service.AuditService;
import com.innbucks.userservice.service.AuthService;
import com.innbucks.userservice.service.CustomerService;
import com.innbucks.userservice.service.LoginRateLimiter;
import com.innbucks.userservice.service.OtpService;
import com.innbucks.userservice.service.TokenRevocationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Authentication", description = "User registration and login endpoints.")
public class AuthController {

    private final AuthService authService;
    private final CustomerService customerService;
    private final TokenRevocationService tokenRevocationService;
    private final OtpService otpService;
    private final JwtUtil jwtUtil;
    private final LoginRateLimiter loginRateLimiter;
    private final AuditService auditService;

    @PostMapping("/register")
    @SecurityRequirements()
    @Operation(summary = "Register system user",
            description = "Submits a system-user account for approval. **No password is supplied here** — the " +
                    "account is created inactive and pending SUPER_ADMIN approval. On approval (the first " +
                    "activation via `PUT /admin/users/{id}/active`) the account is assigned the default password " +
                    "`#Pass123` and flagged to change it on first login. " +
                    "The caller picks one or more `defaultServices` (`ticketing`, `loyalty`); the server derives " +
                    "the role and the underlying microservice access. `ticketing` -> EVENT_ORGANIZER " +
                    "(events/seats/bookings/payments). `loyalty` -> MERCHANT_ADMIN (loyalty/payments). Picking " +
                    "both grants both. When `isBusiness` is true, `businessName`, `businessAddress` and " +
                    "`bpoNumber` are required. Customers must use the tiered /auth/customer/register endpoints.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201",
                    description = "Registration submitted, pending approval",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = AuthResponseDTO.class),
                            examples = @ExampleObject(name = "Pending approval", value = """
                                    {
                                      "code": "201 CREATED",
                                      "message": "Registration submitted. Your account is pending approval by a SUPER_ADMIN.",
                                      "data": {
                                        "roles": ["EVENT_ORGANIZER"],
                                        "defaultServices": ["ticketing"],
                                        "email": "alice@innbucks.co.zw",
                                        "mfaRequired": false,
                                        "mustChangePassword": false
                                      }
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "Validation failed, or email / phone already registered",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Validation failed", value = """
                                    {
                                      "code": "400 BAD_REQUEST",
                                      "message": "Validation failed",
                                      "data": {
                                        "email": "Email is required",
                                        "businessNameValid": "businessName is required for a business account"
                                      }
                                    }
                                    """),
                            @ExampleObject(name = "Already registered", value = """
                                    {
                                      "code": "400 BAD_REQUEST",
                                      "message": "Email already registered",
                                      "data": null
                                    }
                                    """)
                    }))
    })
    public ResponseEntity<ApiResult<AuthResponseDTO>> register(@Valid @RequestBody RegisterRequestDTO request) {
        log.info("Received registration request email={}", request.getEmail());
        AuthResponseDTO response = authService.register(request);
        log.info("Registration submitted, pending approval email={}", request.getEmail());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.created("Registration submitted. Your account is pending approval by a SUPER_ADMIN.", response));
    }

    @PostMapping("/login")
    @SecurityRequirements()
    @Operation(summary = "Login (customer or system user)",
            description = """
                    Authenticates a user and returns a JWT bearer token.

                    **Identifier:** supply a single `identifier` field containing either an email address or a phone
                    number, together with `password`. The server picks the matching lookup based on whether the value
                    contains an `@`.
                    - Customers registered at tier 1 typically log in with their phone number.
                    - System users (EVENT_ORGANIZER, MERCHANT_ADMIN) log in with email.

                    **Tier / verified claims:** on success the response includes the customer's `tier` (1..4) and `verified` flag.
                    These are also embedded in the JWT as claims, so every downstream service can enforce tier-based access
                    without re-querying user-service. System users get `verified=true`; `tier` is null for system accounts.

                    **Using the token:** send it on every authenticated request as `Authorization: Bearer <token>`.
                    To pick up a new tier after the customer upgrades (e.g. tier 2 → tier 3), log in again to receive
                    a fresh token — the old token keeps its original tier claim until it expires.
                    """)
            @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "Login successful",
                    content = @Content(
                            mediaType = "application/json",
                            examples = {
                                    @ExampleObject(name = "Customer login (phone-only)", value = """
                                            {
                                              "code": "200 OK",
                                              "message": "Login successful",
                                              "data": {
                                                "token": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIrMjYzNzcxMjM0NTY3Iiwicm9sZXMiOlsiQ1VTVE9NRVIiXSwic2VydmljZXMiOltdLCJ0aWVyIjoyLCJ2ZXJpZmllZCI6ZmFsc2UsInBob25lTnVtYmVyIjoiKzI2Mzc3MTIzNDU2NyIsImZpcnN0TmFtZSI6IkphbmUiLCJsYXN0TmFtZSI6IkRvZSIsImlhdCI6MTcxNTY2NTYwMCwiZXhwIjoxNzE1NzUyMDAwfQ.access-signature",
                                                "refreshToken": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIrMjYzNzcxMjM0NTY3IiwidHlwZSI6InJlZnJlc2giLCJpYXQiOjE3MTU2NjU2MDAsImV4cCI6MTcxNjI3MDQwMH0.refresh-signature",
                                                "roles": ["CUSTOMER"],
                                                "defaultServices": [],
                                                "mfaRequired": false,
                                                "tier": 2,
                                                "verified": false
                                              }
                                            }
                                            """),
                                    @ExampleObject(name = "System user login", value = """
                                            {
                                              "code": "200 OK",
                                              "message": "Login successful",
                                              "data": {
                                                "token": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhbGljZUBpbm5idWNrcy5jby56dyIsInJvbGVzIjpbIkVWRU5UX09SR0FOSVpFUiJdLCJzZXJ2aWNlcyI6WyJldmVudC1zZXJ2aWNlIiwiYm9va2luZy1zZXJ2aWNlIiwic2VhdC1zZXJ2aWNlIiwicGF5bWVudC1zZXJ2aWNlIl0sInRpZXIiOjQsInZlcmlmaWVkIjp0cnVlLCJpYXQiOjE3MTU2NjU2MDAsImV4cCI6MTcxNTc1MjAwMH0.access-signature",
                                                "refreshToken": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhbGljZUBpbm5idWNrcy5jby56dyIsInR5cGUiOiJyZWZyZXNoIiwiaWF0IjoxNzE1NjY1NjAwLCJleHAiOjE3MTYyNzA0MDB9.refresh-signature",
                                                "email": "alice@innbucks.co.zw",
                                                "roles": ["EVENT_ORGANIZER"],
                                                "defaultServices": ["ticketing"],
                                                "mfaRequired": false,
                                                "tier": 4,
                                                "verified": true
                                              }
                                            }
                                            """)
                            })),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid credentials or missing identifier"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "423",
                    description = "Account locked due to too many consecutive failed login attempts " +
                            "(default 7). The `lockedUntil` ISO-8601 timestamp tells the FE when " +
                            "the next attempt will be accepted; `retryAfterSeconds` is the same " +
                            "deadline expressed in seconds from now and is mirrored in the " +
                            "`Retry-After` header. A successful login on a non-locked account " +
                            "resets the strike counter; an expired lockout resets it on the next " +
                            "attempt.",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Account locked", value = """
                                    {
                                      "code": "423 LOCKED",
                                      "message": "Account temporarily locked due to too many failed login attempts",
                                      "data": {
                                        "lockedUntil": "2026-05-19T14:05:00Z",
                                        "retryAfterSeconds": 1800
                                      }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429",
                    description = "Rate limit exceeded on this identifier or source IP. Body contains a " +
                            "`retryAfterSeconds` hint; the same value is mirrored in the `Retry-After` header.",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Too many login attempts", value = """
                                    {
                                      "code": "429 TOO_MANY_REQUESTS",
                                      "message": "Too many login attempts on this account; try again shortly",
                                      "data": { "retryAfterSeconds": 60 }
                                    }
                                    """)))
    })
    public ResponseEntity<ApiResult<AuthResponseDTO>> login(
            HttpServletRequest httpRequest,
            @Valid @RequestBody LoginRequestDTO request,
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId) {
        loginRateLimiter.checkLogin(request.getIdentifier(), clientIp(httpRequest));
        log.info("Received login request identifier={} hasDeviceId={}",
                request.getIdentifier(), deviceId != null && !deviceId.isBlank());
        AuthResponseDTO response = authService.login(request, deviceId, auditContext(httpRequest));
        log.info("Login successful roles={}", response.getRoles());
        return ResponseEntity.ok(ApiResult.ok("Login successful", response));
    }

    @PostMapping("/refresh")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Rotate refresh token (and pick up updated tier/verified)",
            description = """
                    Exchanges a refresh token for a brand-new access token + refresh token pair.
                    Reads the latest `tier`, `verified`, roles and bundles from the database,
                    so this is the way to pick up a tier upgrade (e.g. tier 1 → tier 2)
                    without a full re-login.

                    **Required bearer token type:** must be the `refreshToken` returned by
                    `/auth/login` (or by a previous call to `/auth/refresh`). Access tokens
                    are rejected with HTTP 400 — they cannot be used for refresh.

                    **Strict rotation with reuse detection:** every call consumes the supplied
                    refresh token (it is marked revoked and CANNOT be used again) and mints a
                    fresh one linked to the same family. If a refresh token that has already
                    been rotated is presented again, the entire token family is revoked
                    immediately — this catches credential theft. After family revocation, the
                    user must log in again to get a new family.

                    Clients should treat the response as a complete replacement: discard the
                    old refresh token the moment the new one is received and store the new
                    `refreshToken` atomically alongside the new access `token`.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "Fresh token issued",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Token refreshed",
                                      "data": {
                                        "token": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIrMjYzNzcxMjM0NTY3Iiwicm9sZXMiOlsiQ1VTVE9NRVIiXSwic2VydmljZXMiOltdLCJ0aWVyIjoyLCJ2ZXJpZmllZCI6ZmFsc2UsInBob25lTnVtYmVyIjoiKzI2Mzc3MTIzNDU2NyIsImZpcnN0TmFtZSI6IkphbmUiLCJsYXN0TmFtZSI6IkRvZSIsImlhdCI6MTcxNTY2OTIwMCwiZXhwIjoxNzE1NzU1NjAwfQ.access-signature",
                                        "refreshToken": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIrMjYzNzcxMjM0NTY3IiwidHlwZSI6InJlZnJlc2giLCJpYXQiOjE3MTU2NjkyMDAsImV4cCI6MTcxNjI3NDAwMH0.refresh-signature",
                                        "roles": ["CUSTOMER"],
                                        "defaultServices": [],
                                        "mfaRequired": false,
                                        "tier": 2,
                                        "verified": false
                                      }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "Refresh token is invalid, expired, not of type=refresh, or has already been rotated " +
                            "(reuse — the entire family is revoked).",
                    content = @Content(mediaType = "application/json",
                            examples = {
                                    @ExampleObject(name = "Already rotated (reuse)", value = """
                                            {
                                              "code": "400 BAD_REQUEST",
                                              "message": "Refresh token reuse detected; family revoked",
                                              "data": null
                                            }
                                            """),
                                    @ExampleObject(name = "Wrong token type", value = """
                                            {
                                              "code": "400 BAD_REQUEST",
                                              "message": "Not a refresh token",
                                              "data": null
                                            }
                                            """),
                                    @ExampleObject(name = "Expired or unknown", value = """
                                            {
                                              "code": "400 BAD_REQUEST",
                                              "message": "Refresh token not recognised",
                                              "data": null
                                            }
                                            """)
                            })),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "Missing or malformed bearer header",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "401 UNAUTHORIZED",
                                      "message": "Missing Bearer token",
                                      "data": null
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429",
                    description = "Rate limit exceeded on this refresh-token subject or source IP. " +
                            "Body carries the same `retryAfterSeconds` value as the `Retry-After` header.",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Too many refresh attempts", value = """
                                    {
                                      "code": "429 TOO_MANY_REQUESTS",
                                      "message": "Too many refresh attempts from this address; try again shortly",
                                      "data": { "retryAfterSeconds": 60 }
                                    }
                                    """)))
    })
    public ResponseEntity<ApiResult<AuthResponseDTO>> refresh(
            HttpServletRequest request,
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResult.error(HttpStatus.UNAUTHORIZED, "Missing Bearer token"));
        }
        String token = authHeader.substring(7);
        // Rate-limit BEFORE attempting rotation. extractEmail returns
        // null on a malformed/expired token — the per-IP bucket still
        // counts so a host spamming garbage tokens gets throttled.
        String subject = safeSubject(token);
        loginRateLimiter.checkRefresh(subject, clientIp(request));
        AuthResponseDTO response = authService.refresh(token, deviceId, auditContext(request));
        return ResponseEntity.ok(ApiResult.ok("Token refreshed", response));
    }

    @ExceptionHandler(LoginRateLimiter.RateLimitedException.class)
    public ResponseEntity<ApiResult<RateLimitDetail>> handleRateLimited(
            LoginRateLimiter.RateLimitedException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Integer.toString(ex.getRetryAfterSeconds()))
                .body(ApiResult.of(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage(),
                        new RateLimitDetail(ex.getRetryAfterSeconds())));
    }

    @ExceptionHandler(AuthService.AccountLockedException.class)
    public ResponseEntity<ApiResult<AccountLockedDetail>> handleAccountLocked(
            AuthService.AccountLockedException ex) {
        long retryAfterSeconds = Math.max(0,
                java.time.Duration.between(java.time.Instant.now(), ex.getLockedUntil()).getSeconds());
        return ResponseEntity.status(HttpStatus.LOCKED)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds))
                .body(ApiResult.of(HttpStatus.LOCKED, ex.getMessage(),
                        new AccountLockedDetail(ex.getLockedUntil(), retryAfterSeconds)));
    }

    /**
     * Body payload for 429 responses. The seconds hint matches the
     * {@code Retry-After} header; clients that already display friendly
     * countdowns can read either source.
     */
    public record RateLimitDetail(int retryAfterSeconds) {}

    /**
     * Body payload for 423 LOCKED responses. {@code lockedUntil} is the
     * absolute ISO-8601 deadline (FE can render a countdown);
     * {@code retryAfterSeconds} is the same deadline expressed as
     * seconds-from-now and is mirrored in the {@code Retry-After}
     * header. Both fields stay in the body even when the header is
     * present so a relative-clock FE has a fallback.
     */
    public record AccountLockedDetail(java.time.Instant lockedUntil, long retryAfterSeconds) {}

    /**
     * Stamp the audit row with the request's source IP + user agent.
     * Same X-Forwarded-For-aware IP resolution {@link #clientIp} uses,
     * so the limiter bucket and the audit row agree on attribution.
     */
    private static AuditContext auditContext(HttpServletRequest request) {
        return new AuditContext(clientIp(request), request.getHeader("User-Agent"));
    }

    /**
     * Best-effort source-IP extraction. Behind the api-gateway every
     * request carries an {@code X-Forwarded-For} chain; the leftmost
     * entry is the originating client. Falling back to
     * {@code remoteAddr} keeps direct-to-service callers (curl, dev)
     * from skipping the limit.
     */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            String first = (comma < 0 ? forwarded : forwarded.substring(0, comma)).trim();
            if (!first.isEmpty()) return first;
        }
        return request.getRemoteAddr();
    }

    private String safeSubject(String token) {
        try {
            return jwtUtil.extractEmail(token);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    @PostMapping("/change-password")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Change the caller's password",
            description = """
                    Replaces the current password with a new one. Both `currentPassword` and `newPassword`
                    are required — the current password is checked against the stored hash to prevent a
                    stolen-token attacker from locking the legitimate user out.

                    Used by shop staff to replace the default password (`#Pass123`) stamped at onboarding
                    by their merchant/shop admin. The existing JWT continues to work until it expires;
                    no re-login is required.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "Password changed",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Password changed",
                                      "data": null
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "Validation failure, wrong current password, or new password matches the current one"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "Missing or invalid bearer token")
    })
    public ResponseEntity<ApiResult<Void>> changePassword(HttpServletRequest request,
                                                          @Valid @RequestBody ChangePasswordRequestDTO body) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResult.error(HttpStatus.UNAUTHORIZED, "Missing Bearer token"));
        }
        String token = authHeader.substring(7);
        authService.changePassword(token, body, auditContext(request));
        return ResponseEntity.ok(ApiResult.ok("Password changed", null));
    }

    @PostMapping("/logout")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Logout",
            description = """
                    Revokes the JWT supplied in the `Authorization: Bearer <token>` header.

                    The token's SHA-256 hash is added to a server-side denylist. Any subsequent request
                    to user-service that presents the same token will be treated as unauthenticated.

                    **Scope note:** today the denylist is checked only in user-service. Other services
                    (booking, seat, event) continue to accept the token until it expires naturally. For
                    a full logout, the client should also delete its stored token.

                    Safe to call multiple times — revoking an already-revoked token is a no-op.
                    """)
            @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Token revoked",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(name = "Logout successful", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Logout successful",
                                      "data": null
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Missing or malformed Authorization header, or token already expired")
    })
    public ResponseEntity<ApiResult<Void>> logout(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ResponseEntity.badRequest()
                    .body(ApiResult.error(HttpStatus.BAD_REQUEST, "Missing Bearer token"));
        }
        String token = authHeader.substring(7);
        tokenRevocationService.revoke(token);
        // Audit attribution from the bearer's subject — the token is
        // already valid enough to reach this point (the controller
        // doesn't verify it further; revoke is idempotent).
        String subject = safeSubject(token);
        auditService.recordSuccess(
                AuditEventType.AUTH_LOGOUT,
                subject, AuditService.ACTOR_TYPE_USER,
                subject, AuditService.TARGET_TYPE_USER,
                null, auditContext(request));
        log.info("Logout successful");
        return ResponseEntity.ok(ApiResult.ok("Logout successful", null));
    }

    @PostMapping("/customer/register")
    @SecurityRequirements()
    @Operation(summary = "Customer registration - Tier 1",
            description = "Tier 1: Creates a customer account with phone number and password only. " +
                    "Does NOT return a JWT token — customers authenticate separately via POST /auth/login.")
    @ApiResponses(@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201",
            description = "Customer created; OTP dispatched to the phone for verification.",
            content = @Content(mediaType = "application/json",
                    examples = @ExampleObject(value = """
                            {
                              "code": "201 CREATED",
                              "message": "Customer tier 1 registration successful",
                              "data": {
                                "userId": 42,
                                "phoneNumber": "+263771234567",
                                "tier": 1,
                                "verified": false,
                                "nextStep": "Verify phone at /auth/otp/verify, then submit personal details at /auth/customer/register/tier2"
                              }
                            }
                            """))))
    public ResponseEntity<ApiResult<CustomerRegistrationResponseDTO>> customerTier1(
            @Valid @RequestBody CustomerTier1RegisterDTO request) {
        CustomerRegistrationResponseDTO response = customerService.registerTier1(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.created("Customer tier 1 registration successful", response));
    }

    @PostMapping("/customer/register/tier2")
    @SecurityRequirements()
    @Operation(summary = "Customer registration - Tier 2",
            description = "Tier 2: Captures full name, date of birth, gender, national ID, email, structured " +
                    "postal address, and an open `clientCustomFields` map. The `msisdn` field identifies the " +
                    "Tier-1 customer to upgrade (phone supplied at /auth/customer/register).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "Tier 2 complete.",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Customer tier 2 registration successful",
                                      "data": {
                                        "userId": 42,
                                        "phoneNumber": "0712345678",
                                        "tier": 2,
                                        "verified": false,
                                        "nextStep": "Submit biometrics and device registration at /auth/customer/register/tier3"
                                      }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "Validation failure or no Tier-1 customer matches the supplied `msisdn`.",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "400 BAD_REQUEST",
                                      "message": "Customer not found for the supplied phone number",
                                      "data": null
                                    }
                                    """)))
    })
    public ResponseEntity<ApiResult<CustomerRegistrationResponseDTO>> customerTier2(
            @Valid @RequestBody CustomerTier2RegisterDTO request) {
        CustomerRegistrationResponseDTO response = customerService.registerTier2(request);
        return ResponseEntity.ok(ApiResult.ok("Customer tier 2 registration successful", response));
    }

    @PostMapping("/customer/register/tier3")
    @SecurityRequirements()
    @Operation(summary = "Customer registration - Tier 3",
            description = "Tier 3: Captures biometrics reference and registers a device for the customer.")
    @ApiResponses(@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
            description = "Tier 3 complete.",
            content = @Content(mediaType = "application/json",
                    examples = @ExampleObject(value = """
                            {
                              "code": "200 OK",
                              "message": "Customer tier 3 registration successful",
                              "data": {
                                "userId": 42,
                                "phoneNumber": "+263771234567",
                                "tier": 3,
                                "verified": false,
                                "nextStep": "Upload verification documents at /auth/customer/register/tier4"
                              }
                            }
                            """))))
    public ResponseEntity<ApiResult<CustomerRegistrationResponseDTO>> customerTier3(
            @RequestParam("phoneNumber") String phoneNumber,
            @Valid @RequestBody CustomerTier3RegisterDTO request) {
        CustomerRegistrationResponseDTO response = customerService.registerTier3(phoneNumber, request);
        return ResponseEntity.ok(ApiResult.ok("Customer tier 3 registration successful", response));
    }

    @PostMapping("/customer/register/tier4")
    @SecurityRequirements()
    @Operation(summary = "Customer registration - Tier 4 (verification)",
            description = "Tier 4: Captures uploaded ID document, proof of residence, and passport document paths and marks the customer as verified.")
    @ApiResponses(@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
            description = "Tier 4 verification complete.",
            content = @Content(mediaType = "application/json",
                    examples = @ExampleObject(value = """
                            {
                              "code": "200 OK",
                              "message": "Customer verification complete",
                              "data": {
                                "userId": 42,
                                "phoneNumber": "+263771234567",
                                "tier": 4,
                                "verified": true
                              }
                            }
                            """))))
    public ResponseEntity<ApiResult<CustomerRegistrationResponseDTO>> customerTier4(
            @RequestParam("phoneNumber") String phoneNumber,
            @Valid @RequestBody CustomerTier4RegisterDTO request) {
        CustomerRegistrationResponseDTO response = customerService.registerTier4(phoneNumber, request);
        return ResponseEntity.ok(ApiResult.ok("Customer verification complete", response));
    }

    @GetMapping("/customer/tier")
    @SecurityRequirements()
    @Operation(summary = "Get customer registration tier by phone number",
            description = "Returns the customer's current registration tier (1..4) along with the next tier they can progress to. " +
                    "`nextTier` is omitted when the customer is already at the maximum tier (4).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "Customer tier retrieved",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Customer tier retrieved",
                                      "data": {
                                        "phoneNumber": "+263771234567",
                                        "currentTier": 2,
                                        "nextTier": 3
                                      }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Customer not found for the supplied phone number")
    })
    public ResponseEntity<ApiResult<CustomerTierResponseDTO>> getCustomerTier(
            @RequestParam("phoneNumber") String phoneNumber) {
        log.info("Get customer tier phone={}", MsisdnMasking.mask(phoneNumber));
        CustomerTierResponseDTO response = customerService.getCustomerTierByPhoneNumber(phoneNumber);
        return ResponseEntity.ok(ApiResult.ok("Customer tier retrieved", response));
    }

    @GetMapping("/customer/deposits")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "List my Oradian deposit accounts",
            description = """
                    Returns the authenticated customer's Oradian deposit accounts.

                    The customer's phone number is taken from the `phoneNumber` claim
                    on the bearer JWT — the caller doesn't pass it explicitly, and
                    a customer can only ever read their own deposits. user-service
                    forwards the phone to Oradian middleware's S2S
                    /internal/customers/{msisdn}/deposits endpoint, which calls
                    Oradian's instafin.LookupClient and returns just the deposits
                    array. We pass that array through unchanged.

                    A tier-1 customer (no Oradian Person+Client yet) gets a 400 —
                    same shape as the existing tier-not-met errors. Tokens that
                    aren't customer tokens (no phoneNumber claim) get a 400 as
                    well.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "Deposits returned (empty array if Oradian has none)",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Deposits", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Deposits retrieved",
                                      "data": [
                                        {
                                          "internalID": "",
                                          "ID": "A8347323",
                                          "externalAccountNumber": "",
                                          "clientInternalID": "",
                                          "productID": "fixed_deposit_12",
                                          "productName": "Fixed Deposit 12 Months",
                                          "balance": "7500.00",
                                          "currencyCode": "",
                                          "status": "Active",
                                          "isMainAccount": "true",
                                          "isMessagingFeeAccount": "",
                                          "isJointAccount": "",
                                          "subscribed": "200.00",
                                          "appliedDate": "2018-11-05",
                                          "startDate": "2018-11-05",
                                          "endDate": "2020-11-05",
                                          "closeDate": "2019-11-05"
                                        }
                                      ]
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "Token has no phoneNumber claim, or the phone doesn't resolve to a customer"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Missing or invalid bearer token"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "Oradian middleware unreachable or upstream Oradian failed")
    })
    public ResponseEntity<ApiResult<List<DepositAccount>>> getCustomerDeposits(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResult.error(HttpStatus.UNAUTHORIZED, "Missing Bearer token"));
        }
        String token = authHeader.substring(7);
        String phoneNumber = jwtUtil.extractPhoneNumber(token);
        if (phoneNumber == null || phoneNumber.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ApiResult.error(HttpStatus.BAD_REQUEST,
                            "Token has no phoneNumber claim; only CUSTOMER tokens can call /auth/customer/deposits"));
        }
        log.info("GET /auth/customer/deposits phoneNumber={}", MsisdnMasking.mask(phoneNumber));
        List<DepositAccount> deposits = customerService.getDepositsForCustomer(phoneNumber);
        return ResponseEntity.ok(ApiResult.ok("Deposits retrieved", deposits));
    }

    @GetMapping("/customer/send-money/details/{customerPhoneNumber}")
    @Operation(summary = "Get a customer's send-money details by phone",
            description = """
                    Returns the recipient customer's deposit-account identifiers
                    (account ID, product, currency, status, joint/main flags) so
                    a sender can pick which account to send money to.

                    Uses the same upstream as /auth/customer/deposits (Oradian
                    middleware's /internal/customers/{msisdn}/deposits, backed by
                    instafin.LookupClient). The response strips balance,
                    subscribed, and lifecycle-date fields so a sender doesn't
                    see the recipient's private balance or account history.

                    Phone comes from the path — the caller is expected to have
                    already verified the recipient's identity (e.g. via the
                    SuperApp's contact picker).
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "Customer details retrieved (empty array if Oradian has none)",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Send-money details", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Customer Details retrieved",
                                      "data": [
                                        {
                                          "firstName": "Jane",
                                          "middleName": "M",
                                          "lastName": "Doe",
                                          "internalID": "",
                                          "ID": "A8347323",
                                          "externalAccountNumber": "",
                                          "clientInternalID": "",
                                          "productID": "fixed_deposit_12",
                                          "productName": "Fixed Deposit 12 Months",
                                          "currencyCode": "",
                                          "status": "Active",
                                          "isMainAccount": "true",
                                          "isMessagingFeeAccount": "",
                                          "isJointAccount": ""
                                        }
                                      ]
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "Phone doesn't resolve to a customer"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502",
                    description = "Oradian middleware unreachable or upstream Oradian failed")
    })
    public ResponseEntity<ApiResult<List<CustomerSendMoneyDetail>>> getCustomerSendMoneyDetails(
            @PathVariable String customerPhoneNumber) {
        log.info("GET /auth/customer/send-money/details phoneNumber={}", MsisdnMasking.mask(customerPhoneNumber));
        List<CustomerSendMoneyDetail> details =
                customerService.getSendMoneyDetailsForCustomer(customerPhoneNumber);
        return ResponseEntity.ok(ApiResult.ok("Customer Details retrieved", details));
    }

    @PostMapping("/otp/request")
    @SecurityRequirements()
    @Operation(summary = "Request (or re-send) an OTP",
            description = """
                    Generates a fresh 6-digit OTP for the supplied phone number and (nominally) sends it by SMS.
                    Any previously-issued OTP for that phone is invalidated.

                    **Development note:** delivery is stubbed — the OTP is always `000000` and is written to the
                    user-service logs instead of being sent by SMS. This will be swapped for a real provider later.

                    **Rate limit:** a phone number may request at most **3 OTPs within any 10-minute window**.
                    The 4th request triggers a **30-minute lockout** (HTTP 429). A successful OTP verification
                    resets the counter.
                    """)
            @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "OTP sent",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(name = "OTP sent", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "OTP sent",
                                      "data": null
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Missing or invalid phone number"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "Retry quota exceeded — try again after the lockout expires")
    })
    public ResponseEntity<ApiResult<Void>> requestOtp(@Valid @RequestBody OtpRequestDTO request) {
        log.info("OTP request phone={}", MsisdnMasking.mask(request.getPhoneNumber()));
        try {
            otpService.sendOtp(request.getPhoneNumber());
        } catch (OtpService.OtpRateLimitException e) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiResult.error(HttpStatus.TOO_MANY_REQUESTS, e.getMessage()));
        }
        return ResponseEntity.ok(ApiResult.ok("OTP sent", null));
    }

    @PostMapping("/otp/verify")
    @SecurityRequirements()
    @Operation(summary = "Verify an OTP",
            description = """
                    Verifies the 6-digit OTP for the supplied phone number. On success the OTP is consumed
                    (deleted from the database) and — if the phone belongs to a customer — their
                    `phoneVerified` flag is flipped to `true`. Three wrong submissions invalidate the OTP,
                    forcing the user to request a fresh one.
                    """)
            @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "OTP verified and consumed",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(name = "OTP verified", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "OTP verified",
                                      "data": null
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "OTP invalid or expired")
    })
    public ResponseEntity<ApiResult<Void>> verifyOtp(@Valid @RequestBody OtpVerifyDTO request) {
        log.info("OTP verify phone={}", MsisdnMasking.mask(request.getPhoneNumber()));
        boolean ok = otpService.verifyOtp(request.getPhoneNumber(), request.getCode());
        if (!ok) {
            return ResponseEntity.badRequest()
                    .body(ApiResult.error(HttpStatus.BAD_REQUEST, "Invalid or expired OTP"));
        }
        return ResponseEntity.ok(ApiResult.ok("OTP verified", null));
    }
}
