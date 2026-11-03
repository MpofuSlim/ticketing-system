package com.innbucks.bookingservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** {@code PUT /bookings/numbering/{categoryId}}: how a category's tickets are numbered (V34). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A ticket category's number series. Every field is optional.")
public class NumberingRequestDTO {

    @Size(max = 20, message = "At most 20 characters.")
    @Schema(description = "What the number is called on the ticket and at the gate. Default \"Number\".",
            example = "Bib", maxLength = 20)
    private String label;

    @Pattern(regexp = "^[A-Za-z0-9-]{0,10}$", message = "Letters, digits and hyphens only, at most 10.")
    @Schema(description = "Put in front of every number. Letters, digits and hyphens only.", example = "T",
            maxLength = 10)
    private String prefix;

    @Min(value = 0, message = "Must be between 0 and 9999999.")
    @Max(value = 9_999_999, message = "Must be between 0 and 9999999.")
    @Schema(description = "The first number. Default 1.", example = "1")
    private Integer start;

    @Min(value = 0, message = "Must be between 0 and 9.")
    @Max(value = 9, message = "Must be between 0 and 9.")
    @Schema(description = "Digits shown, filled with zeros in front: 3 gives 001. 0 = no zeros. Default 0.",
            example = "3")
    private Integer padding;
}
