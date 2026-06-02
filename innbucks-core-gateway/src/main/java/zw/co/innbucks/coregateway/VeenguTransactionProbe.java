package zw.co.innbucks.coregateway;

import feign.FeignException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import zw.co.innbucks.core.dto.TransactionDto;
import zw.co.innbucks.core.dto.enums.Channel;
import zw.co.innbucks.core.dto.enums.Currency;
import zw.co.innbucks.core.dto.enums.PaymentTypeDto;
import zw.co.innbucks.core.dto.enums.TransactionTypeDto;
import zw.co.innbucks.core.rest.client.VeenguClient;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Milestone-2 contract-discovery probe for the WRITE path ({@code postTransaction}).
 *
 * <p><b>Body-driven.</b> POST any subset of {@link TransactionDto} fields as JSON and
 * the probe forwards it to veengu verbatim, returning veengu's reply. This lets us
 * iterate on the required-field contract with {@code curl} alone — no rebuilds per
 * guess. An empty body falls back to a minimal fixed test transaction (the previous
 * zero-arg behaviour). Enum fields accept their names, e.g.
 * {@code "currency":"USD","channel":"API","extendedType":"PAYMENT"}.
 *
 * <p>veengu identifies the caller via the {@code X-Source-Component} header that
 * core's {@code FeignInterceptor} attaches; no per-request participantId is needed.
 *
 * <p>POST-only so a stray GET can't trigger it. A blank reference is auto-filled so
 * each call is traceable in {@code /var/log/apps/veengu-integration-human.log}.
 *
 * <p>WARNING: on a veengu wired to live rails, this is a REAL payment. Only run once
 * the target environment is confirmed sandbox.
 */
@RestController
class VeenguTransactionProbe {

    private static final Logger log = LoggerFactory.getLogger(VeenguTransactionProbe.class);

    private final VeenguClient veenguClient;

    VeenguTransactionProbe(VeenguClient veenguClient) {
        this.veenguClient = veenguClient;
    }

    @PostMapping("/veengu/transaction-probe")
    Map<String, Object> probe(@RequestBody(required = false) TransactionDto request) {
        // No body -> minimal fixed transaction, so a bare POST still works as before.
        if (request == null) {
            request = TransactionDto.builder()
                    .amount(new BigDecimal("1.00"))
                    .currency(Currency.USD)
                    .paymentType(PaymentTypeDto.ACCOUNT)
                    .channel(Channel.API)
                    .transactionType(TransactionTypeDto.PAYMENT)
                    .build();
        }
        // Always stamp a reference if the caller didn't, so the attempt is traceable
        // in veengu's logs by the same value we echo back here.
        if (request.getReference() == null || request.getReference().isBlank()) {
            request.setReference("TKT-PROBE-" + System.currentTimeMillis());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sent", request);
        try {
            TransactionDto response = veenguClient.postTransaction(request);
            result.put("ok", true);
            result.put("reachedVeengu", true);
            result.put("response", response);
        } catch (FeignException e) {
            // Reached veengu; it answered with a non-2xx. The body is the contract we want.
            log.warn("[veengu] postTransaction probe (ref={}) -> HTTP {}", request.getReference(), e.status());
            result.put("ok", false);
            result.put("reachedVeengu", true);
            result.put("status", e.status());
            result.put("message", e.getMessage());
        } catch (Exception e) {
            // Never reached veengu — discovery or connectivity failure.
            log.warn("[veengu] postTransaction probe (ref={}) failed before reaching veengu",
                    request.getReference(), e);
            result.put("ok", false);
            result.put("reachedVeengu", false);
            result.put("error", e.toString());
        }
        return result;
    }
}
