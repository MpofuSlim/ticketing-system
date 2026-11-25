package innbucks.paymentservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** The code O'mari texted the customer, for {@code POST /payments/{transactionId}/otp}. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "PaymentOtpRequest")
public class PaymentOtpRequest {

    @NotBlank
    @Size(max = 16)
    @Schema(example = "482913", requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Required. The code from the SMS O'mari sent to the booking's phone, digits only.")
    private String otp;
}
