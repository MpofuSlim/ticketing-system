package com.innbucks.userservice.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CustomerTier4RegisterDTO {

    @NotBlank(message = "ID document path is required")
    private String idDocumentPath;

    @NotBlank(message = "Proof of residence path is required")
    private String proofOfResidencePath;

    private String passportDocumentPath;
}
