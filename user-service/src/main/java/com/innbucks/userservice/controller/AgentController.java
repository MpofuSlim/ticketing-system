package com.innbucks.userservice.controller;

import com.innbucks.userservice.dto.AgentProfileDTO;
import com.innbucks.userservice.service.AgentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@CrossOrigin
@RequestMapping("/agent")
@RequiredArgsConstructor
@Tag(name = "Agent", description = "Agent-specific profile endpoints.")
public class AgentController {

    private final AgentService agentService;

    @GetMapping("/profile")
    @Operation(summary = "Get agent profile", description = "Returns the profile of the authenticated AGENT user.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Profile returned"),
            @ApiResponse(responseCode = "401", description = "Missing/invalid JWT"),
            @ApiResponse(responseCode = "403", description = "Authenticated user is not an AGENT")
    })
    public ResponseEntity<AgentProfileDTO> getProfile(Authentication authentication) {

        String email = authentication.getName();
        return ResponseEntity.ok(agentService.getProfile(email));
    }
}
