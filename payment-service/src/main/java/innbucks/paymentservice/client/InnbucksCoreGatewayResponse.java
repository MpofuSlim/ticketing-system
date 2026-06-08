package innbucks.paymentservice.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Inbound response envelope from innbucks-core-gateway's {@code /payments/*}
 * endpoints. The gateway returns this both on success AND on terminal
 * rejections (e.g. insufficient funds) as HTTP 200 — the {@code outcome}
 * field is the single source of truth, NOT the HTTP status.
 *
 * <p>HTTP 503 with this envelope means the gateway couldn't reach veengu;
 * {@link InnbucksCoreGatewayClient} translates it to
 * {@link InnbucksCoreGatewayTransientException}, not a returned response.
 *
 * <p>Unknown fields are ignored so a future gateway version that adds new
 * envelope fields doesn't break us.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InnbucksCoreGatewayResponse(
        String paymentReference,
        PaymentOutcome outcome,
        String upstreamReference,
        String upstreamCode,
        String upstreamMessage,
        String error
) {}
