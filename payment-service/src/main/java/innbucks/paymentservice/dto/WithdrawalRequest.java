package innbucks.paymentservice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Request body for POST /payments/withdraw (FE → payment-service) and the
 * payload payment-service forwards to Oradian middleware's
 * EnterWithdrawalOnDepositAccount endpoint (Oradian middleware in turn
 * proxies onto Oradian's instafin.EnterWithdrawalOnDepositAccount).
 *
 * <p>Three fields are server-stamped — Jackson ignores them on the inbound
 * FE deserialisation ({@link JsonProperty.Access#READ_ONLY}) so a client
 * can't backdate a withdrawal, can't choose the booking branch, and can't
 * bypass Oradian's limit checks:
 * <ul>
 *   <li>{@code transactionDate} → today (stamped in {@code PublicTransferController}).</li>
 *   <li>{@code transactionBranchID} → hardcoded {@code "MobileBanking"} for now;
 *       will graduate to a config property when we deploy in markets that need
 *       a different branch ID.</li>
 *   <li>{@code overrideLimitCheck} → always {@code false}; customer-initiated
 *       withdrawals must respect Oradian's product / daily / per-transaction
 *       limits. Bypassing them is operator-tools territory and is not exposed
 *       here.</li>
 * </ul>
 * Amount is a String because Oradian's wire format expects a quoted decimal
 * (e.g. {@code "10.00"}).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "WithdrawalRequest",
        description = "Withdrawal payload submitted by an authenticated customer.")
public class WithdrawalRequest {

    @NotBlank(message = "accountID is required")
    @Schema(example = "A000015",
            description = "Oradian deposit account ID to withdraw from. MUST belong to the authenticated customer; " +
                    "payment-service verifies this against the JWT-derived phone number before forwarding.")
    private String accountID;

    @NotBlank(message = "paymentMethodName is required")
    @Size(max = 200, message = "paymentMethodName must be at most 200 characters")
    @Schema(example = "Cash",
            description = "Payment method name as configured in Oradian (e.g. \"Cash\"). The valid values come " +
                    "from the deployment's Oradian configuration; the FE picker should populate them from there.")
    private String paymentMethodName;

    @NotBlank(message = "amount is required")
    @Schema(example = "10.00",
            description = "Withdrawal amount as a string — Oradian's wire format. Use a plain decimal like \"10.00\".")
    private String amount;

    @Size(max = 500, message = "notes must be at most 500 characters")
    @Schema(example = "Cash out at agent",
            description = "Optional free-text notes from the FE. May be null or empty.")
    private String notes;

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @Schema(accessMode = Schema.AccessMode.READ_ONLY,
            description = "Server-stamped on POST /payments/withdraw; clients do not supply this.",
            example = "2026-05-18")
    private LocalDate transactionDate;

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @Schema(accessMode = Schema.AccessMode.READ_ONLY,
            description = "Server-set to \"MobileBanking\"; clients do not supply this.",
            example = "MobileBanking")
    private String transactionBranchID;

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @Schema(accessMode = Schema.AccessMode.READ_ONLY,
            description = "Server-set to false; customer-initiated withdrawals must respect Oradian's limit checks.",
            example = "false")
    private Boolean overrideLimitCheck;
}
