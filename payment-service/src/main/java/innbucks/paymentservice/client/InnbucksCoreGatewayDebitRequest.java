package innbucks.paymentservice.client;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

import java.math.BigDecimal;

/**
 * Outbound request body to innbucks-core-gateway's {@code POST /payments/debit}.
 * Field names are the gateway's wire contract — renaming any of them
 * silently breaks the call.
 *
 * <p>{@code currency} is sent as the ISO 4217 three-letter code (the gateway
 * deserialises it back to {@code zw.co.innbucks.core.dto.enums.Currency} on
 * its side). We keep it as a {@link String} on our side so we don't have to
 * pull the core jar in here just for one enum.
 *
 * <p>Null fields are dropped from the JSON via {@link JsonInclude.Include#NON_NULL}
 * — the gateway only inspects the fields it needs and ignores extras, but
 * a smaller payload reads better in logs.
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InnbucksCoreGatewayDebitRequest(
        String paymentReference,
        String customerMsisdn,
        String customerAccount,
        String merchantAccount,
        BigDecimal amount,
        String currency,
        String narration,
        String participantId
) {}
