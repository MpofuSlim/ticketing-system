package com.innbucks.bookingservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** {@code POST /bookings/box-office/{bookingId}/void} (V35). */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Why a box office cash sale is being voided")
public class BoxOfficeVoidRequestDTO {

    @NotBlank(message = "Please say why the sale is being voided.")
    @Size(max = 500, message = "At most 500 characters.")
    @Schema(example = "Buyer changed their mind", maxLength = 500)
    private String reason;
}
