package com.innbucks.userservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Payload for a SUPER_ADMIN rejecting a pending service request.
 *
 * <p>The reason is REQUIRED, deliberately. It is sent to the requester, so a
 * blank one would tell them their request was refused and nothing else — which
 * is what makes people re-submit the identical request. It is also what a
 * second admin reads to see what a colleague already decided.
 */
@Data
@Schema(name = "RejectServiceRequest",
        description = "Payload for a SUPER_ADMIN to reject a pending service-bundle request.")
public class RejectServiceRequestDTO {

    @NotBlank(message = "reason is required")
    @Size(max = 1000, message = "reason must be 1000 characters or fewer")
    @Schema(example = "Your business verification is still outstanding — please complete it and re-apply.",
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Why the request was refused. Shown to the requester verbatim, so write it "
                        + "for them rather than as an internal note.")
    private String reason;
}
