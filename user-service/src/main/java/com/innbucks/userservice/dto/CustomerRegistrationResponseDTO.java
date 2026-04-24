package com.innbucks.userservice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(
        name = "CustomerRegistrationResponse",
        description = "Returned from the tiered customer registration endpoints. " +
                "Does NOT include a JWT token or an email — customers authenticate separately via POST /auth/login " +
                "using their phone number."
)
public class CustomerRegistrationResponseDTO {

    @Schema(description = "Internal user ID", example = "42")
    private Long userId;

    @Schema(description = "Phone number the customer registered with.", example = "+263771234567")
    private String phoneNumber;

    @Schema(description = "Highest tier the customer has completed (1..4).", example = "1")
    private int tier;

    @Schema(description = "True once the customer has completed tier 4 document verification.", example = "false")
    private boolean verified;

    @Schema(description = "Hint for the client describing the next tier endpoint to call, or null at tier 4.",
            example = "Submit personal details at /auth/customer/register/tier2",
            nullable = true)
    private String nextStep;
}
