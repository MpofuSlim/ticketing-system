package com.innbucks.userservice.controller;

import com.innbucks.userservice.dto.ApiResult;
import com.innbucks.userservice.dto.StaffContactDTO;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Security + behaviour tests for the internal staff-contact lookup consumed by
 * loyalty-service's STAFF_RECIPIENT earn guard.
 *
 * <p>Mirrors {@link InternalUserLookupControllerTest}: the token check rejects
 * with a SPECIFIC 401 asserted directly on the status (never a vague
 * {@code is4xxClientError()}, per CLAUDE.md), and the repository is only
 * queried once the shared X-Internal-Token matches. An unknown merchant is an
 * EMPTY list, deliberately not a 404 — no merchant-existence oracle on the
 * S2S surface, and the consuming guard treats both identically.
 */
class InternalShopStaffControllerTest {

    private static final String TOKEN = "the-shared-secret";
    private static final UUID MERCHANT = UUID.randomUUID();

    private InternalShopStaffController controller(UserRepository repo, String expectedToken) {
        InternalTokenAuthorizer authorizer =
                new InternalTokenAuthorizer(expectedToken, mock(AuditService.class));
        return new InternalShopStaffController(repo, authorizer);
    }

    private static HttpServletRequest request() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn("/users/internal/shop-staff/by-merchant/x/contacts");
        when(req.getRemoteAddr()).thenReturn("203.0.113.7");
        return req;
    }

    private static User staff(String phone) {
        return User.builder()
                .userUuid(UUID.randomUUID())
                .firstName("Tariro")
                .lastName("Ncube")
                .phoneNumber(phone)
                .email("till@example.com")
                .password("x")
                .build();
    }

    @Test
    @SuppressWarnings("unchecked")
    void validToken_returnsTrimmedContacts_includingPhonelessAccounts() {
        UserRepository repo = mock(UserRepository.class);
        // One phone-carrying cashier, one email-only account: BOTH come back —
        // the consumer filters nulls, and dropping rows here would hide the
        // userUuid a future pair-detection report needs.
        when(repo.findByLoyaltyMerchantId(MERCHANT))
                .thenReturn(List.of(staff("+263771234567"), staff(null)));

        ResponseEntity<?> resp = controller(repo, TOKEN).staffContacts(TOKEN, MERCHANT, request());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<StaffContactDTO> data =
                (List<StaffContactDTO>) ((ApiResult<?>) resp.getBody()).getData();
        assertThat(data).hasSize(2);
        assertThat(data.get(0).getPhoneNumber()).isEqualTo("+263771234567");
        assertThat(data.get(0).getUserUuid()).isNotNull();
        assertThat(data.get(1).getPhoneNumber()).isNull();
    }

    @Test
    void unknownMerchant_isAnEmptyList_notA404() {
        UserRepository repo = mock(UserRepository.class);
        when(repo.findByLoyaltyMerchantId(MERCHANT)).thenReturn(List.of());

        ResponseEntity<?> resp = controller(repo, TOKEN).staffContacts(TOKEN, MERCHANT, request());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) ((ApiResult<?>) resp.getBody()).getData()).isEmpty();
    }

    @Test
    void wrongToken_returns401_andNeverTouchesTheRepository() {
        UserRepository repo = mock(UserRepository.class);

        ResponseEntity<?> resp = controller(repo, TOKEN)
                .staffContacts("not-the-secret", MERCHANT, request());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(repo, never()).findByLoyaltyMerchantId(MERCHANT);
    }

    @Test
    void missingToken_returns401() {
        UserRepository repo = mock(UserRepository.class);

        ResponseEntity<?> resp = controller(repo, TOKEN).staffContacts(null, MERCHANT, request());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(repo, never()).findByLoyaltyMerchantId(MERCHANT);
    }

    @Test
    void serverTokenUnset_rejectsEveryone_evenAMatchingBlank() {
        // Deploy-time misconfig must fail CLOSED: with no expected token
        // configured, no presented value — including blank — is accepted.
        UserRepository repo = mock(UserRepository.class);

        ResponseEntity<?> resp = controller(repo, "").staffContacts("", MERCHANT, request());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(repo, never()).findByLoyaltyMerchantId(MERCHANT);
    }
}
