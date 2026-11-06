package com.innbucks.userservice.controller;

import com.innbucks.userservice.dto.ApiResult;
import com.innbucks.userservice.dto.SalesAgentInviteDTOs;
import com.innbucks.userservice.service.SalesAgentInviteService;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * (S2S) event-service's half of the sales-agent portal invite (V56): mint and
 * email an invite when an organizer gives a selling team member Foundry portal
 * access, and revoke a team member's open invites when that access is removed.
 *
 * <p>Internal like the rest of {@code /users/internal/**}: the shared
 * {@code X-Internal-Token} is checked FIRST, constant-time
 * ({@link InternalTokenAuthorizer}), before the body or the path is read, so an
 * unauthenticated call is a 401 whatever it carries. {@code SecurityConfig}
 * permits the path so that check runs; the gateway's {@code user-internal-deny}
 * route keeps it off the public internet. {@link Hidden} keeps it out of public
 * Swagger.
 */
@RestController
@RequestMapping("/users/internal/sales-agent-invites")
@RequiredArgsConstructor
@Hidden
public class InternalSalesAgentInviteController {

    private final InternalTokenAuthorizer tokenAuthorizer;
    private final SalesAgentInviteService salesAgentInvites;

    @PostMapping
    @Operation(summary = "(S2S) Email a sales agent their Foundry portal invite",
            description = "202 `{expiresAt}` once the invite is stored; the email goes after commit. A new invite "
                    + "for the same teamMemberId revokes its earlier open ones. 400 `invalid_request` / "
                    + "`invalid_phone` / `invalid_email` / `email_domain_reserved`; 401 without the internal "
                    + "token; 503 `sales_agent_invites_unconfigured` when no link can be built or sent.")
    public ResponseEntity<ApiResult<SalesAgentInviteDTOs.Created>> create(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestBody(required = false) SalesAgentInviteDTOs.CreateRequest body,
            HttpServletRequest request) {
        if (!tokenAuthorizer.authorized(token, request)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        SalesAgentInviteDTOs.Created created = salesAgentInvites.create(body);
        return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore())
                .body(ApiResult.of(HttpStatus.ACCEPTED, SalesAgentInviteService.CREATED_MESSAGE, created));
    }

    @DeleteMapping("/{teamMemberId}")
    @Operation(summary = "(S2S) Revoke a sales agent's open portal invites",
            description = "200 `{revoked}`: how many live links stopped working (0 is fine). Never removes an "
                    + "account's role or password. 400 `invalid_request` for a teamMemberId that is not a UUID; "
                    + "401 without the internal token.")
    public ResponseEntity<ApiResult<SalesAgentInviteDTOs.Revoked>> revoke(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @PathVariable("teamMemberId") String teamMemberId,
            HttpServletRequest request) {
        if (!tokenAuthorizer.authorized(token, request)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(ApiResult.ok("Portal invites revoked", salesAgentInvites.revoke(teamMemberId)));
    }
}
