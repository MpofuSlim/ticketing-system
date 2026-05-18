package com.innbucks.userservice.client;

import com.innbucks.userservice.config.OradianProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;

@Slf4j
@Component
public class OradianClient {

    private static final String CUSTOMERS_PATH = "/internal/customers";
    private static final String DEPOSITS_PATH = "/internal/customers/{msisdn}/deposits";
    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";
    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final RestClient restClient;
    private final OradianProperties properties;

    public OradianClient(@Qualifier("oradianRestClient") RestClient restClient,
                         OradianProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    /**
     * Create a customer in Oradian via the middleware's S2S endpoint.
     *
     * <p>{@code idempotencyKey} MUST be stable per customer (e.g. derived
     * from {@code User.id}), not freshly generated per call. Reason: this
     * endpoint may end up "Oradian-committed but local-rolled-back" if
     * anything between the Oradian response and the local transaction
     * commit fails. On retry, the same key lets Oradian middleware return
     * the cached response (24h window) so the local profile can be
     * stamped with the existing externalID / clientID instead of either
     * orphaning the Oradian record or double-creating. Past 24h the
     * cache evicts and Oradian's own duplicate-NationalID check is the
     * backstop — currently surfaces as a 409 we don't auto-recover from
     * (operator/reconciliation job territory).
     */
    public OradianCustomerResponse createCustomer(OradianCustomerRequest request, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException(
                    "idempotencyKey must be non-blank — retries with a fresh key would orphan " +
                    "the Oradian record if the local transaction rolled back.");
        }
        try {
            OradianCustomerResponse response = restClient.post()
                    .uri(CUSTOMERS_PATH)
                    .header(INTERNAL_TOKEN_HEADER, properties.getInternalToken())
                    .header(IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(OradianCustomerResponse.class);
            log.info("Oradian create succeeded msisdn={} customerId={} oradianClientId={} idemKey={}",
                    request.getMsisdn(),
                    response != null ? response.getCustomerId() : null,
                    response != null ? response.getOradianClientId() : null,
                    idempotencyKey);
            return response;
        } catch (RestClientResponseException ex) {
            log.warn("Oradian create failed msisdn={} idemKey={} status={} body={}",
                    request.getMsisdn(), idempotencyKey, ex.getStatusCode(), ex.getResponseBodyAsString());
            throw new OradianClientException(
                    "Oradian middleware rejected the customer create: HTTP " + ex.getStatusCode().value(), ex);
        } catch (RuntimeException ex) {
            log.warn("Oradian create failed msisdn={} idemKey={} message={}",
                    request.getMsisdn(), idempotencyKey, ex.getMessage());
            throw new OradianClientException(
                    "Oradian middleware is unreachable: " + ex.getMessage(), ex);
        }
    }

    /**
     * GET the customer's deposit accounts from Oradian middleware's S2S
     * endpoint. The middleware does the local customer lookup by msisdn,
     * calls Oradian's instafin.LookupClient, and returns only the deposits
     * array — we pass that array through to our caller unchanged.
     *
     * Returns an empty list on a 200 with no body or a null body. Translates
     * HTTP/IO failures into OradianClientException so callers (and
     * GlobalExceptionHandler) can surface them as a 502 to the customer.
     */
    public List<DepositAccount> getDeposits(String msisdn) {
        try {
            List<DepositAccount> response = restClient.get()
                    .uri(DEPOSITS_PATH, msisdn)
                    .header(INTERNAL_TOKEN_HEADER, properties.getInternalToken())
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<DepositAccount>>() {});
            log.info("Oradian deposits lookup succeeded msisdn={} count={}",
                    msisdn, response == null ? 0 : response.size());
            return response == null ? List.of() : response;
        } catch (RestClientResponseException ex) {
            log.warn("Oradian deposits lookup failed msisdn={} status={} body={}",
                    msisdn, ex.getStatusCode(), ex.getResponseBodyAsString());
            throw new OradianClientException(
                    "Oradian middleware rejected the deposits lookup: HTTP " + ex.getStatusCode().value(), ex);
        } catch (RuntimeException ex) {
            log.warn("Oradian deposits lookup failed msisdn={} message={}", msisdn, ex.getMessage());
            throw new OradianClientException(
                    "Oradian middleware is unreachable: " + ex.getMessage(), ex);
        }
    }
}
