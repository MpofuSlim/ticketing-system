package innbucks.paymentservice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Response body for POST /payments/withdraw. Mirrors Oradian middleware's
 * EnterWithdrawalOnDepositAccountResponse field-for-field so the upstream
 * envelope passes through unchanged — callers get the same Oradian-assigned
 * transactionID / commandID / referenceNumber they would see if they hit
 * the middleware directly.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(name = "WithdrawalResponse",
        description = "Echoed request + Oradian-assigned identifiers.")
public class WithdrawalResponse {
    private Boolean overrideLimitCheck;
    private String accountID;
    private String paymentMethodName;
    private LocalDate transactionDate;
    private String amount;
    private String transactionBranchID;
    private String notes;
    private String referenceNumber;
    private String transactionID;
    private String commandID;
}
