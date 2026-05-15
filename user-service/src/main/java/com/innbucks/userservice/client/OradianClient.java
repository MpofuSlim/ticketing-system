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
import java.util.UUID;

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

    public OradianCustomerResponse createCustomer(OradianCustomerRequest request) {
        String idempotencyKey = UUID.randomUUID().toString();
        try {
            OradianCustomerResponse response = restClient.post()
                    .uri(CUSTOMERS_PATH)
                    .header(INTERNAL_TOKEN_HEADER, properties.getInternalToken())
                    .header(IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(OradianCustomerResponse.class);
            log.info("Oradian create succeeded msisdn={} customerId={} oradianClientId={}",
                    request.getMsisdn(),
                    response != null ? response.getCustomerId() : null,
                    response != null ? response.getOradianClientId() : null);
            return response;
        } catch (RestClientResponseException ex) {
            log.warn("Oradian create failed msisdn={} status={} body={}",
                    request.getMsisdn(), ex.getStatusCode(), ex.getResponseBodyAsString());
            throw new OradianClientException(
                    "Oradian middleware rejected the customer create: HTTP " + ex.getStatusCode().value(), ex);
        } catch (RuntimeException ex) {
            log.warn("Oradian create failed msisdn={} message={}", request.getMsisdn(), ex.getMessage());
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
