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
 * Request body for POST /payments/transfer (FE → payment-service) and the
 * payload payment-service forwards to Oradian middleware's
 * SubmitDepositAccountTransferRequest (which in turn mirrors Oradian's
 * instafin.SubmitDepositAccountTransfer). `amount` is a String because
 * Oradian's wire format expects "123.00" (quoted) for some product
 * configurations — we don't try to second-guess that here.
 *
 * <p>{@code transactionDate} is server-stamped: Jackson ignores it on the
 * inbound deserialization ({@link JsonProperty.Access#READ_ONLY}) so a
 * client can't predate or postdate a transfer, and PublicTransferController
 * sets it to today before forwarding. It stays a regular serialized field
 * on the outbound call so Oradian middleware still receives it.
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

    @Size(max = 500, message = "notes must be at most 500 characters")
    @Schema(example = "Lunch",
            description = "Optional free-text notes from the FE. Required by Oradian's wire " +
                    "format though, so payment-service coerces a missing/null value to \"\" " +
                    "before forwarding.")
    private String notes;

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @Schema(accessMode = Schema.AccessMode.READ_ONLY,
            description = "Server-stamped on POST /payments/transfer; clients do not supply this. " +
                    "Included on the response (and on the outbound call to Oradian middleware).",
            example = "2026-05-18")
    private LocalDate transactionDate;
}
