package com.innbucks.userservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "OtpVerifyResponse",
        description = "Result of verifying an OTP. Carries the short-lived, single-use verification token a "
                + "customer must present to complete tier-2/3/4 KYC upgrade — proof that they just controlled "
                + "this phone.")
public class OtpVerifyResponseDTO {

    @Schema(description = "The phone number that was verified.", example = "+263771234567")
    private String phoneNumber;

    @Schema(description = "Always true on a 200 response.", example = "true")
    private boolean verified;

    @Schema(description = "Single-use, short-lived JWT proving this phone was just OTP-verified. Send it in the "
            + "X-Verification-Token header on POST /auth/customer/register/tier2|tier3|tier4. Without it those "
            + "endpoints return 401.",
            example = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIrMjYzNzcxMjM0NTY3In0...")
    private String verificationToken;

    @Schema(description = "Seconds until the verification token expires.", example = "900")
    private long verificationTokenExpiresInSeconds;
}
