package innbucks.paymentservice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Response body for POST /internal/transfers/deposit. Mirrors Oradian
 * middleware's SubmitDepositAccountTransferResponse field-for-field so we
 * can pass the upstream envelope through unchanged — callers get the same
 * transactionID / referenceNumber / FX fields they would see if they hit
 * the middleware directly.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(name = "DepositTransferResponse",
        description = "Echoed request + Oradian-assigned identifiers and (optional) FX details.")
public class DepositTransferResponse {
    private String fromAccountId;
    private String toAccountId;
    private String amount;
    private String paymentAmount;
    private String paymentCurrency;
    private String customExchangeRate;
    private String notes;
    private String referenceNumber;
    private LocalDate transactionDate;
    private String transactionID;
    private String accountVersion;
    private String customFields;
}
