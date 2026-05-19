package innbucks.paymentservice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * One Oradian deposit-account row, mirroring Oradian middleware's
 * <code>GET /internal/customers/{msisdn}/deposits</code> response shape.
 * Field types and names match the middleware DTO verbatim so Jackson can
 * deserialise without remapping. Wire-format quirks (balance / boolean flags
 * arriving as strings, empty strings instead of nulls) are preserved.
 *
 * <p>Used by {@link innbucks.paymentservice.client.OradianMiddlewareClient}'s
 * deposits lookup, which the public deposit-transfer endpoint calls to verify
 * that the caller actually owns the source account.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(name = "DepositAccount", description = "One Oradian deposit account row.")
public class DepositAccount {
    private String internalID;
    private String ID;
    private String externalAccountNumber;
    private String clientInternalID;
    private String productID;
    private String productName;
    private String balance;
    private String currencyCode;
    private String status;
    private String isMainAccount;
    private String isMessagingFeeAccount;
    private String isJointAccount;
    private String subscribed;
    private LocalDate appliedDate;
    private LocalDate startDate;
    private LocalDate endDate;
    private LocalDate closeDate;
}
