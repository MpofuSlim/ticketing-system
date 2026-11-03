package com.innbucks.bookingservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(name = "GateLookupRequest",
        description = "Body for gate staff to find a booking by the confirmation number the customer "
                      + "reads out or shows from their SMS — the fallback for a customer who cannot "
                      + "open the WhatsApp QR. Case and surrounding spaces are ignored.")
public class GateLookupRequestDTO {

    @NotBlank(message = "confirmationNumber is required")
    @Size(max = 64, message = "confirmationNumber is too long")
    @Schema(example = "INN-20260901-3C8849",
            description = "The booking's confirmation number, as printed in the SMS/WhatsApp messages.")
    private String confirmationNumber;

    @Schema(example = "5772554b-9c1d-4e2f-a3b4-c5d6e7f8a9b0", nullable = true,
            description = "The event the scanner is scanning for. REQUIRED from a super-app scanner; "
                    + "optional for the scanner app. A booking for any other event is WRONG_EVENT.")
    private java.util.UUID eventId;
}
