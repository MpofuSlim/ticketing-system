package com.innbucks.userservice.corebanking;

import com.innbucks.userservice.client.DepositAccount;
import com.innbucks.userservice.client.OradianClient;
import com.innbucks.userservice.client.OradianClientException;
import com.innbucks.userservice.client.OradianCustomerRequest;
import com.innbucks.userservice.client.OradianCustomerResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Pins {@link OradianCoreBankingAdapter}'s translation layer: the SPI
 * extraction must be a PURE refactor, so the wire request the middleware
 * sees, the Oradian-specific guards (gender, empty body), and the exception
 * semantics must be byte-for-byte what CustomerService produced when this
 * logic lived inline.
 */
class OradianCoreBankingAdapterTest {

    private static CoreBankingCreateCustomerCommand command(String gender) {
        Map<String, String> ccf = new LinkedHashMap<>();
        ccf.put("MembershipTier", "2");
        return CoreBankingCreateCustomerCommand.builder()
                .firstName("Alice")
                .middleName("M")
                .lastName("Moyo")
                .dateOfBirth(LocalDate.of(1995, 4, 12))
                .gender(gender)
                .msisdn("+254712345678")
                .nationalId("12345678")
                .email("alice@example.com")
                .address(CoreBankingCreateCustomerCommand.Address.builder()
                        .street1("1 Main St").city("Nairobi").postCode("00100").country("KE")
                        .build())
                .clientCustomFields(ccf)
                .build();
    }

    private static OradianCustomerResponse response() {
        OradianCustomerResponse r = new OradianCustomerResponse();
        r.setCustomerId("cust-uuid-1");
        r.setOradianExternalId("ext-1");
        r.setOradianClientId(777L);
        r.setClientStatus("PENDING_APPROVAL");
        r.setCountry("KE");
        return r;
    }

    @Test
    void provider_isOradian() {
        assertThat(new OradianCoreBankingAdapter(mock(OradianClient.class)).provider())
                .isEqualTo("ORADIAN");
    }

    @Test
    void createCustomer_mapsEveryCommandFieldOntoTheWireRequest() {
        OradianClient client = mock(OradianClient.class);
        when(client.createCustomer(any(), anyString())).thenReturn(response());
        OradianCoreBankingAdapter adapter = new OradianCoreBankingAdapter(client);

        adapter.createCustomer(command("FEMALE"), "customer-tier-2:42");

        ArgumentCaptor<OradianCustomerRequest> req = ArgumentCaptor.forClass(OradianCustomerRequest.class);
        verify(client).createCustomer(req.capture(), eq("customer-tier-2:42"));
        OradianCustomerRequest wire = req.getValue();
        assertThat(wire.getFirstName()).isEqualTo("Alice");
        assertThat(wire.getMiddleName()).isEqualTo("M");
        assertThat(wire.getLastName()).isEqualTo("Moyo");
        assertThat(wire.getDateOfBirth()).isEqualTo(LocalDate.of(1995, 4, 12));
        assertThat(wire.getGender()).isEqualTo("FEMALE");
        assertThat(wire.getMsisdn()).isEqualTo("+254712345678");
        assertThat(wire.getNationalId()).isEqualTo("12345678");
        assertThat(wire.getEmail()).isEqualTo("alice@example.com");
        assertThat(wire.getAddress().getStreet1()).isEqualTo("1 Main St");
        assertThat(wire.getAddress().getCity()).isEqualTo("Nairobi");
        assertThat(wire.getAddress().getPostCode()).isEqualTo("00100");
        assertThat(wire.getAddress().getCountry()).isEqualTo("KE");
        assertThat(wire.getClientCustomFields()).containsEntry("MembershipTier", "2");
    }

    @Test
    void createCustomer_mapsResponse_profileRefIsTheExternalId() {
        OradianClient client = mock(OradianClient.class);
        when(client.createCustomer(any(), anyString())).thenReturn(response());

        CoreBankingCustomerResult result = new OradianCoreBankingAdapter(client)
                .createCustomer(command("FEMALE"), "k");

        assertThat(result.profileRef()).isEqualTo("ext-1");
        assertThat(result.oradianExternalId()).isEqualTo("ext-1");
        assertThat(result.oradianClientId()).isEqualTo(777L);
        assertThat(result.status()).isEqualTo("PENDING_APPROVAL");
        assertThat(result.country()).isEqualTo("KE");
    }

    @Test
    void createCustomer_genderOther_isRejectedBeforeTheWireCall() {
        // Oradian's enum only knows MALE/FEMALE — the guard moved here from
        // CustomerService.toOradianRequest and must keep the same exception
        // type + message (GlobalExceptionHandler maps it to 502).
        OradianClient client = mock(OradianClient.class);

        assertThatThrownBy(() -> new OradianCoreBankingAdapter(client)
                .createCustomer(command("OTHER"), "k"))
                .isInstanceOf(OradianClientException.class)
                .hasMessageContaining("gender=OTHER");
        verifyNoInteractions(client);
    }

    @Test
    void createCustomer_nullResponseBody_throwsContractViolation() {
        OradianClient client = mock(OradianClient.class);
        when(client.createCustomer(any(), anyString())).thenReturn(null);

        assertThatThrownBy(() -> new OradianCoreBankingAdapter(client)
                .createCustomer(command("MALE"), "k"))
                .isInstanceOf(OradianClientException.class)
                .hasMessageContaining("empty response body");
    }

    @Test
    void createCustomer_clientFailure_propagatesUnwrapped() {
        // The adapter must not swallow or re-wrap — the caller's @Transactional
        // rollback and the 502 mapping depend on OradianClientException escaping.
        OradianClient client = mock(OradianClient.class);
        when(client.createCustomer(any(), anyString()))
                .thenThrow(new OradianClientException("Oradian middleware is unreachable: boom"));

        assertThatThrownBy(() -> new OradianCoreBankingAdapter(client)
                .createCustomer(command("MALE"), "k"))
                .isInstanceOf(OradianClientException.class)
                .hasMessageContaining("unreachable");
    }

    @Test
    void listDeposits_passesThrough() {
        OradianClient client = mock(OradianClient.class);
        DepositAccount acct = new DepositAccount();
        when(client.getDeposits("+254712345678")).thenReturn(List.of(acct));

        List<DepositAccount> out = new OradianCoreBankingAdapter(client).listDeposits("+254712345678");

        assertThat(out).containsExactly(acct);
    }
}
