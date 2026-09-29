package com.innbucks.userservice.devicesecurity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Wire shapes of the broker-only DTX endpoints (contract §5.4 and the ticket check of §3 rule 3). */
public final class BrokerDTOs {

    private BrokerDTOs() {
    }

    @Schema(name = "DtxLoginResultRequest",
            description = "How staging's user login went, reported right after staging answers (§5.4). No PIN, no token.")
    public record LoginResultRequest(
            @NotBlank(message = "ticketId is required")
            @Size(max = 40, message = "ticketId is not valid")
            @Schema(example = "tkt_3n8p1r5t7v9x2z4b6d8f0h2k4m", requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "The loginTicket's jti.")
            String ticketId,
            @NotBlank(message = "outcome is required")
            @Pattern(regexp = "SUCCESS|WRONG_PIN|LOCKED|PIN_NOT_SET|ERROR",
                    message = "outcome must be SUCCESS, WRONG_PIN, LOCKED, PIN_NOT_SET or ERROR")
            @Schema(example = "SUCCESS", requiredMode = Schema.RequiredMode.REQUIRED)
            String outcome,
            @Size(max = 32, message = "stagingCode must be 32 characters or fewer")
            @Schema(example = "000", description = "Staging's responseCode, for the audit row.")
            String stagingCode,
            @Schema(example = "2026-09-29T09:58:14.102Z") String at) {
    }

    @Schema(name = "DtxLoginResultResponse")
    public record LoginResultResponse(
            @Schema(example = "tkt_3n8p1r5t7v9x2z4b6d8f0h2k4m") String ticketId,
            @Schema(example = "SUCCESS") String outcome,
            @Schema(example = "true", description = "false = this ticket's outcome was already recorded; nothing changed.")
            boolean recorded,
            @Schema(example = "TRUSTED", nullable = true) String deviceState) {
    }

    @Schema(name = "DtxTicketRedeemRequest",
            description = "Spends a login ticket, checking it against the login it is about to authorise (§3 rule 3).")
    public record TicketRedeemRequest(
            @NotBlank(message = "ticket is required")
            @Size(max = 4096, message = "ticket is too long")
            @Schema(example = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIrMjYzNzcxMjM0NTEyIn0.sig", requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "The x-dtx-ticket header the app sent.")
            String ticket,
            @NotBlank(message = "msisdn is required")
            @Size(max = 20, message = "msisdn must be 20 characters or fewer")
            @Schema(example = "+263771234512", requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "The username on the user login.")
            String msisdn,
            @NotBlank(message = "installId is required")
            @Size(max = 64, message = "installId must be 64 characters or fewer")
            @Schema(example = "8b1e6c0e-4f2a-4d8e-9b3c-1a2b3c4d5e6f", requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "The x-device-id on the user login.")
            String installId,
            @Pattern(regexp = "SIGN_IN|PIN_ISSUE|LOOKUP", message = "purpose must be SIGN_IN, PIN_ISSUE or LOOKUP")
            @Schema(example = "SIGN_IN", description = "The staging call being authorised. Defaults to SIGN_IN.")
            String purpose) {
    }

    @Schema(name = "DtxTicketRedeemResponse")
    public record TicketRedeemResponse(
            @Schema(example = "true", description = "Forward to staging only when true.") boolean valid,
            @Schema(example = "tkt_3n8p1r5t7v9x2z4b6d8f0h2k4m", nullable = true) String ticketId,
            @Schema(example = "SIGN_IN", nullable = true) String purpose,
            @Schema(example = "null", nullable = true,
                    description = "When valid=false: INVALID, EXPIRED, ALREADY_USED, MSISDN_MISMATCH, DEVICE_MISMATCH or PURPOSE_MISMATCH.")
            String reason) {
    }
}
