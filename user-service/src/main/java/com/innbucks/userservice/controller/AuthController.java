package com.innbucks.userservice.controller;

import com.innbucks.userservice.dto.*;
import com.innbucks.userservice.service.AuthService;
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

    @PostMapping("/register")
    @SecurityRequirements()
    @Operation(summary = "Register user", description = "Creates a new user account. Returns profile context without JWT token.")
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
    @Operation(summary = "Login user", description = "Authenticates a user and returns a JWT token.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Login successful"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid credentials")
    })
    public ResponseEntity<ApiResult<AuthResponseDTO>> login(@Valid @RequestBody LoginRequestDTO request) {
        log.info("Received login request email={}", request.getEmail());
        AuthResponseDTO response = authService.login(request);
        if (response.isMfaRequired()) {
            log.info("Login halted for MFA email={}", request.getEmail());
            return ResponseEntity.ok(ApiResult.ok("MFA verification required", response));
        }
        log.info("Login successful email={} role={}", response.getEmail(), response.getRole());
        return ResponseEntity.ok(ApiResult.ok("Login successful", response));
    }
}
