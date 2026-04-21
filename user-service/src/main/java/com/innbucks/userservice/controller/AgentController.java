package com.innbucks.userservice.controller;

import com.innbucks.userservice.dto.AgentProfileDTO;
import com.innbucks.userservice.dto.ApiResult;
import com.innbucks.userservice.service.AgentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@CrossOrigin
@RequestMapping("/agent")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Agent", description = "Agent-specific profile endpoints.")
public class AgentController {

    private final AgentService agentService;

    @GetMapping("/profile")
    @Operation(summary = "Get agent profile", description = "Returns the profile of the authenticated AGENT user.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Profile returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Missing/invalid JWT"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Authenticated user is not an AGENT")
    })
    public ResponseEntity<ApiResult<AgentProfileDTO>> getProfile(Authentication authentication) {
        String email = authentication.getName();
        log.debug("GET /agent/profile email={}", email);
        AgentProfileDTO profile = agentService.getProfile(email);
        return ResponseEntity.ok(ApiResult.ok("Profile retrieved successfully", profile));
    }
}
