package com.innbucks.userservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * Request and response shapes for sales-agent portal invites (V56): the internal
 * {@code /users/internal/sales-agent-invites} calls event-service makes, and the
 * public {@code /auth/sales-agent-invite/**} calls the Foundry portal's invite
 * page makes.
 */
public final class SalesAgentInviteDTOs {

    private SalesAgentInviteDTOs() {}

    /**
     * (S2S) event-service's request. The ids are strings on purpose: they are
     * parsed AFTER the internal token is checked, so a malformed body from an
     * unauthenticated caller is a 401 like any other, never a parse error.
     */
    public record CreateRequest(String teamMemberId, String organizerUuid, String organizerName,
                                String phoneNumber, String email) {

        /** Never print the contact details. */
        @Override
        public String toString() {
            return "CreateRequest[teamMemberId=" + teamMemberId + ", organizerUuid=" + organizerUuid + "]";
        }
    }

    /** (S2S) 202 body: when the link stops working (UTC, rendered with {@code Z} on this internal path). */
    public record Created(LocalDateTime expiresAt) {
    }

    /** (S2S) 200 body of the revoke: how many live links stopped working (0 is fine). */
    public record Revoked(int revoked) {
    }

    @Schema(name = "SalesAgentInviteTokenRequest")
    public record TokenRequest(
            @NotBlank(message = "token is required")
            @Size(max = 128, message = "token is invalid")
            @Schema(example = "SAI-dGhpcy1pcy1ub3QtYS1yZWFsLWludml0ZS10b2tlbgA",
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "The token from the invite link's fragment (#token=…).")
            String token) {

        /** Never print the token. */
        @Override
        public String toString() {
            return "TokenRequest[<redacted>]";
        }
    }

    @Schema(name = "SalesAgentInviteAcceptRequest")
    public record AcceptRequest(
            @NotBlank(message = "token is required")
            @Size(max = 128, message = "token is invalid")
            @Schema(example = "SAI-dGhpcy1pcy1ub3QtYS1yZWFsLWludml0ZS10b2tlbgA",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String token,
            @NotBlank(message = "phoneCode is required")
            @Size(max = 12, message = "phoneCode is invalid")
            @Schema(example = "482913", requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "The code sent to the invite's phone by POST /auth/sales-agent-invite/phone-code.")
            String phoneCode,
            @NotBlank(message = "password is required")
            @Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters")
            @Schema(example = "Correct-Horse-7-Battery", requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "The Foundry portal password, 8 to 72 characters. It also becomes the "
                            + "account's password for signing in with the phone number.")
            String password) {

        /** Never print the token, the code or the password. */
        @Override
        public String toString() {
            return "AcceptRequest[<redacted>]";
        }
    }

    @Schema(name = "SalesAgentInviteInspection")
    public record Inspection(
            @Schema(example = "Harare Arena Events", nullable = true,
                    description = "The organiser who sent the invite.") String organizerName,
            @Schema(example = "tendai.moyo@gmail.com",
                    description = "The address the person will sign in with.") String email,
            @Schema(example = "****4567", description = "The phone the code goes to, masked.") String maskedPhone,
            @Schema(example = "2026-11-09T12:00:00+02:00") LocalDateTime expiresAt) {
    }

    @Schema(name = "SalesAgentInviteAccepted")
    public record Accepted(
            @Schema(example = "tendai.moyo@gmail.com", description = "The address to sign in with.") String email,
            @Schema(example = "SIGN_IN", description = "Always SIGN_IN: no session is issued. The first sign-in "
                    + "sets up two-step verification.") String next) {
    }
}
