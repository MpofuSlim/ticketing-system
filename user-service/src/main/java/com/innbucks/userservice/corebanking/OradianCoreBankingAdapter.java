package com.innbucks.userservice.corebanking;

import com.innbucks.userservice.client.DepositAccount;
import com.innbucks.userservice.client.OradianClient;
import com.innbucks.userservice.client.OradianClientException;
import com.innbucks.userservice.client.OradianCustomerRequest;
import com.innbucks.userservice.client.OradianCustomerResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@link CoreBankingPort} implementation backed by Oradian Instafin via the
 * OradianMiddleware S2S API ({@link OradianClient}). Active when
 * {@code innbucks.core-banking.provider=oradian} — which is the committed
 * default, so every cell behaves exactly as before this SPI existed until
 * its deployment env explicitly flips the provider (the Kenya cell stays
 * here permanently; Zimbabwe moves to the Veengu adapter in a later phase).
 *
 * <p>Carries the Oradian-specific rules that used to live inline in
 * CustomerService: the MALE/FEMALE-only gender constraint and the
 * empty-response-body guard. Both throw {@link OradianClientException} so
 * the failure semantics (transaction rollback + 502 via
 * GlobalExceptionHandler) are byte-for-byte what they were.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "innbucks.core-banking.provider", havingValue = "oradian", matchIfMissing = true)
public class OradianCoreBankingAdapter implements CoreBankingPort {

    public static final String PROVIDER = "ORADIAN";

    private final OradianClient oradianClient;

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public CoreBankingCustomerResult createCustomer(CoreBankingCreateCustomerCommand command,
                                                    String idempotencyKey) {
        OradianCustomerResponse response =
                oradianClient.createCustomer(toOradianRequest(command), idempotencyKey);
        if (response == null) {
            // Defensive: RestClient.body() can return null on an empty 200. We
            // treat that as a contract violation — same as Oradian rejecting us —
            // so the caller's @Transactional rolls back and the customer gets 502.
            throw new OradianClientException("Oradian middleware returned an empty response body");
        }
        return CoreBankingCustomerResult.builder()
                // The externalID is Oradian's stable customer reference — it
                // doubles as the provider-agnostic profileRef.
                .profileRef(response.getOradianExternalId())
                .oradianExternalId(response.getOradianExternalId())
                .oradianClientId(response.getOradianClientId())
                .status(response.getClientStatus())
                .country(response.getCountry())
                .build();
    }

    @Override
    public List<DepositAccount> listDeposits(String msisdn) {
        return oradianClient.getDeposits(msisdn);
    }

    private static OradianCustomerRequest toOradianRequest(CoreBankingCreateCustomerCommand command) {
        // Oradian middleware's Gender enum only knows MALE / FEMALE; OTHER would be
        // rejected at JSON deserialisation. Fail fast here with a readable message
        // instead of relaying Oradian's 400.
        if ("OTHER".equals(command.gender())) {
            throw new OradianClientException(
                    "Oradian middleware does not yet support gender=OTHER. Use MALE or FEMALE.");
        }
        CoreBankingCreateCustomerCommand.Address addr = command.address();
        return OradianCustomerRequest.builder()
                .firstName(command.firstName())
                .middleName(command.middleName())
                .lastName(command.lastName())
                .dateOfBirth(command.dateOfBirth())
                .gender(command.gender())
                .msisdn(command.msisdn())
                .nationalId(command.nationalId())
                .email(command.email())
                .address(OradianCustomerRequest.Address.builder()
                        .street1(addr.street1())
                        .city(addr.city())
                        .postCode(addr.postCode())
                        .country(addr.country())
                        .build())
                .clientCustomFields(command.clientCustomFields())
                .build();
    }
}
