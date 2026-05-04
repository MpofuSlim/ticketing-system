package com.innbucks.userservice.controller;

import com.innbucks.userservice.dto.*;
import com.innbucks.userservice.service.AuthService;
import com.innbucks.userservice.service.CustomerService;
import com.innbucks.userservice.service.OtpService;
import com.innbucks.userservice.service.TokenRevocationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
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

@RestController
@CrossOrigin
@RequestMapping("/auth")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Authentication", description = "User registration and login endpoints.")
public class AuthController {

    private final AuthService authService;
    private final CustomerService customerService;
    private final TokenRevocationService tokenRevocationService;
    private final OtpService otpService;

    @PostMapping("/register")
    @SecurityRequirements()
    @Operation(summary = "Register system user",
            description = "Creates a system-user account (SYSTEM_MANAGER, TENANT, MERCHANT_ADMIN, SHOP_ADMIN, SHOP_USER, ADMIN). " +
                    "Requires device registration and MFA registration. Customers must use the tiered /auth/customer/register endpoints.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "User registered"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation failed or email already exists")
    })
    public ResponseEntity<ApiResult<AuthResponseDTO>> register(@Valid @RequestBody RegisterRequestDTO request) {
        log.info("Received registration request email={}", request.getEmail());
        AuthResponseDTO response = authService.register(request);
        log.info("Successfully registered user email={}", request.getEmail());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.created("User registered successfully", response));
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
                    - System users (TENANT, ADMIN, MERCHANT_ADMIN, SHOP_ADMIN, SHOP_USER, SYSTEM_MANAGER) log in with email.

                    **Tier / verified claims:** on success the response includes the customer's `tier` (1..4) and `verified` flag.
                    These are also embedded in the JWT as claims, so every downstream service can enforce tier-based access
                    without re-querying user-service. System users are reported as tier 4, verified=true.

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
                                                "role": "CUSTOMER",
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
                                                "role": "TENANT",
                                                "tier": 4,
                                                "verified": true
                                              }
                                            }
                                            """)
                            })),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid credentials or missing identifier")
    })
    public ResponseEntity<ApiResult<AuthResponseDTO>> login(@Valid @RequestBody LoginRequestDTO request) {
        log.info("Received login request identifier={}", request.getIdentifier());
        AuthResponseDTO response = authService.login(request);
        log.info("Login successful role={}", response.getRole());
        return ResponseEntity.ok(ApiResult.ok("Login successful", response));
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
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Token revoked"),
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
            description = "Tier 2: Captures fullName, idNumber, passport number, address, gender, and a base64-encoded selfie picture " +
                    "(raw base64 or a data URL such as `data:image/png;base64,...`).")
    @ApiResponses(@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
            description = "Tier 2 complete.",
            content = @Content(mediaType = "application/json",
                    examples = @ExampleObject(value = """
                            {
                              "code": "200 OK",
                              "message": "Customer tier 2 registration successful",
                              "data": {
                                "userId": 42,
                                "phoneNumber": "+263771234567",
                                "tier": 2,
                                "verified": false,
                                "nextStep": "Submit biometrics and device registration at /auth/customer/register/tier3"
                              }
                            }
                            """))))
    public ResponseEntity<ApiResult<CustomerRegistrationResponseDTO>> customerTier2(
            @RequestParam("phoneNumber") String phoneNumber,
            @Valid @RequestBody CustomerTier2RegisterDTO request) {
        CustomerRegistrationResponseDTO response = customerService.registerTier2(phoneNumber, request);
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
        log.info("Get customer tier phone={}", phoneNumber);
        CustomerTierResponseDTO response = customerService.getCustomerTierByPhoneNumber(phoneNumber);
        return ResponseEntity.ok(ApiResult.ok("Customer tier retrieved", response));
    }

    /**
     * Live-tier lookup keyed by the JWT subject (email-or-phone). Used by
     * downstream services so MinTier checks never see a stale tier from a
     * still-valid token. System users return tier 4.
     */
    @GetMapping("/customer/tier/by-subject")
    @SecurityRequirements()
    @Operation(summary = "Get customer registration tier by JWT subject (email or phone)",
            description = "Authoritative live-tier lookup used by booking-service / seat-service to bypass " +
                    "the tier claim baked into a still-valid JWT. Resolves the user by email first, then " +
                    "phone number. System (non-CUSTOMER) users always report tier 4.")
    public ResponseEntity<ApiResult<CustomerTierResponseDTO>> getCustomerTierBySubject(
            @RequestParam("subject") String subject) {
        log.debug("Get customer tier by subject");
        CustomerTierResponseDTO response = customerService.getCustomerTierBySubject(subject);
        return ResponseEntity.ok(ApiResult.ok("Customer tier retrieved", response));
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
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OTP sent"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Missing or invalid phone number"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "Retry quota exceeded — try again after the lockout expires")
    })
    public ResponseEntity<ApiResult<Void>> requestOtp(@Valid @RequestBody OtpRequestDTO request) {
        log.info("OTP request phone={}", request.getPhoneNumber());
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
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OTP verified and consumed"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "OTP invalid or expired")
    })
    public ResponseEntity<ApiResult<Void>> verifyOtp(@Valid @RequestBody OtpVerifyDTO request) {
        log.info("OTP verify phone={}", request.getPhoneNumber());
        boolean ok = otpService.verifyOtp(request.getPhoneNumber(), request.getCode());
        if (!ok) {
            return ResponseEntity.badRequest()
                    .body(ApiResult.error(HttpStatus.BAD_REQUEST, "Invalid or expired OTP"));
        }
        return ResponseEntity.ok(ApiResult.ok("OTP verified", null));
    }
}
