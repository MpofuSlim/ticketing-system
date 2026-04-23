package com.innbucks.userservice.controller;

import com.innbucks.userservice.dto.*;
import com.innbucks.userservice.service.AuthService;
import com.innbucks.userservice.service.CustomerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
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
    @Operation(summary = "Login user",
            description = "Authenticates a user by email OR phone number plus password, and returns a JWT token.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Login successful"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid credentials")
    })
    public ResponseEntity<ApiResult<AuthResponseDTO>> login(@Valid @RequestBody LoginRequestDTO request) {
        log.info("Received login request email={} phone={}", request.getEmail(), request.getPhoneNumber());
        AuthResponseDTO response = authService.login(request);
        if (response.isMfaRequired()) {
            log.info("Login halted for MFA");
            return ResponseEntity.ok(ApiResult.ok("MFA verification required", response));
        }
        log.info("Login successful role={}", response.getRole());
        return ResponseEntity.ok(ApiResult.ok("Login successful", response));
    }

    @PostMapping("/customer/register")
    @SecurityRequirements()
    @Operation(summary = "Customer registration - Tier 1",
            description = "Tier 1: Creates a customer account with phone number and password only.")
    public ResponseEntity<ApiResult<CustomerRegistrationResponseDTO>> customerTier1(
            @Valid @RequestBody CustomerTier1RegisterDTO request) {
        CustomerRegistrationResponseDTO response = customerService.registerTier1(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.created("Customer tier 1 registration successful", response));
    }

    @PostMapping("/customer/register/tier2")
    @SecurityRequirements()
    @Operation(summary = "Customer registration - Tier 2",
            description = "Tier 2: Captures fullName, idNumber, passport number, address, gender, and selfie picture.")
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
    public ResponseEntity<ApiResult<CustomerRegistrationResponseDTO>> customerTier4(
            @RequestParam("phoneNumber") String phoneNumber,
            @Valid @RequestBody CustomerTier4RegisterDTO request) {
        CustomerRegistrationResponseDTO response = customerService.registerTier4(phoneNumber, request);
        return ResponseEntity.ok(ApiResult.ok("Customer verification complete", response));
    }
}
