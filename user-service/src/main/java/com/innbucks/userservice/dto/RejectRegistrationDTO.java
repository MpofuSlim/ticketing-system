package com.innbucks.userservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Payload for rejecting a registration still pending approval
 * ({@code PUT /admin/users/{id}/reject}).
 *
 * <p>The reason is REQUIRED, deliberately, as it is for a rejected service
 * request ({@link RejectServiceRequestDTO}): it is sent to the applicant, so a
 * blank one would tell them they were refused and nothing else — which is what
 * makes people register again with the identical details. It is also kept on
 * the audit row, the only record of the registration once it is removed.
 */
@Data
@Schema(name = "RejectRegistration",
        description = "Payload for rejecting a registration that is still pending approval.")
public class RejectRegistrationDTO {

    @NotBlank(message = "reason is required")
    @Size(max = 1000, message = "reason must be 1000 characters or fewer")
    @Schema(example = "We couldn't verify the BPO number you gave. Please register again with the number on your "
            + "ZIMRA certificate.",
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Why the registration was refused. Sent to the applicant verbatim, so write it for "
                    + "them rather than as an internal note. At most 1000 characters.")
    private String reason;
}
