package com.innbucks.userservice.client;

import com.innbucks.userservice.config.OradianProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.UUID;

@Slf4j
@Component
public class OradianClient {

    private static final String PATH = "/internal/customers";
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
                    .uri(PATH)
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
}
