package innbucks.paymentservice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Request body for POST /internal/transfers/deposit. Mirrors Oradian
 * middleware's SubmitDepositAccountTransferRequest, which in turn mirrors
 * Oradian's instafin.SubmitDepositAccountTransfer. `amount` is a String
 * because Oradian's wire format expects "123.00" (quoted) for some product
 * configurations — we don't try to second-guess that here.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "DepositTransferRequest",
        description = "Deposit account transfer payload forwarded to Oradian middleware.")
public class DepositTransferRequest {

    @NotBlank
    @Schema(example = "A000001", description = "Source Oradian deposit account ID.")
    private String fromAccountId;

    @NotBlank
    @Schema(example = "A000002", description = "Destination Oradian deposit account ID.")
    private String toAccountId;

    @NotBlank
    @Schema(example = "123.00", description = "Amount as a string — matches Oradian's wire format.")
    private String amount;

    @Schema(example = "", description = "Optional free-text notes; may be empty.")
    private String notes;

    @NotNull
    @Schema(example = "2020-02-28", description = "Transaction date (ISO-8601).")
    private LocalDate transactionDate;
}
