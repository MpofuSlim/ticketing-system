package com.innbucks.userservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(name = "CustomerTier4RegisterRequest",
        description = "Document paths for full KYC — completes Tier-4 (verified) status.")
public class CustomerTier4RegisterDTO {

    @Schema(example = "uploads/kyc/alice-moyo/national_id.jpg",
            description = "Server path of the uploaded national ID scan.")
    @NotBlank(message = "ID document path is required")
    private String idDocumentPath;

    @Schema(example = "uploads/kyc/alice-moyo/proof_of_residence.pdf",
            description = "Server path of the uploaded proof-of-residence document (utility bill, bank statement, etc.).")
    @NotBlank(message = "Proof of residence path is required")
    private String proofOfResidencePath;

    @Schema(example = "uploads/kyc/alice-moyo/passport.jpg", nullable = true,
            description = "Server path of the uploaded passport scan. Optional if a national ID was provided.")
    private String passportDocumentPath;
}
