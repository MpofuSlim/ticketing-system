package zw.co.innbucks.coregateway;

import feign.FeignException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
 * <p>Sends one small fixed test transaction so veengu's reply tells us the real
 * contract — required fields, valid enum combinations, sync-vs-callback. With an
 * unrecognised participant it should be REJECTED at validation (no money moves);
 * that rejection is exactly the spec we want.
 *
 * <p>POST-only so a stray GET can't trigger it. Amount/currency/enums are fixed test
 * values; only participantId varies.
 *
 * <p>WARNING: pointed at a VALID participant on a veengu wired to live rails, this is
 * a REAL payment. Only use a real participant once the environment is confirmed sandbox.
 */
@RestController
class VeenguTransactionProbe {

    private static final Logger log = LoggerFactory.getLogger(VeenguTransactionProbe.class);

    private final VeenguClient veenguClient;

    VeenguTransactionProbe(VeenguClient veenguClient) {
        this.veenguClient = veenguClient;
    }

    @PostMapping("/veengu/transaction-probe/{participantId}")
    Map<String, Object> probe(@PathVariable String participantId) {
        TransactionDto request = TransactionDto.builder()
                .participantId(participantId)
                .amount(new BigDecimal("1.00"))
                .currency(Currency.USD)
                .paymentType(PaymentTypeDto.ACCOUNT)
                .channel(Channel.API)
                .transactionType(TransactionTypeDto.PAYMENT)
                .reference("TKT-PROBE-" + System.currentTimeMillis())
                .narration("ticketing adapter contract probe")
                .build();

        Map<String, Object> sent = new LinkedHashMap<>();
        sent.put("participantId", participantId);
        sent.put("amount", "1.00");
        sent.put("currency", "USD");
        sent.put("paymentType", "ACCOUNT");
        sent.put("channel", "API");
        sent.put("transactionType", "PAYMENT");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sent", sent);
        try {
            TransactionDto response = veenguClient.postTransaction(request);
            result.put("ok", true);
            result.put("reachedVeengu", true);
            result.put("response", response);
        } catch (FeignException e) {
            // Reached veengu; it answered with a non-2xx. The body is the contract we want.
            log.warn("[veengu] postTransaction(participant={}) -> HTTP {}", participantId, e.status());
            result.put("ok", false);
            result.put("reachedVeengu", true);
            result.put("status", e.status());
            result.put("message", e.getMessage());
        } catch (Exception e) {
            // Never reached veengu — discovery or connectivity failure.
            log.warn("[veengu] postTransaction(participant={}) failed before reaching veengu", participantId, e);
            result.put("ok", false);
            result.put("reachedVeengu", false);
            result.put("error", e.toString());
        }
        return result;
    }
}
