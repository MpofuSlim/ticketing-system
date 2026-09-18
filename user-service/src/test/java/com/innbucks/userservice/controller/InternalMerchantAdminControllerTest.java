package com.innbucks.userservice.controller;

import com.innbucks.userservice.dto.ApiResult;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.integration.LoyaltyServiceClient;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The merchantId → admin-user chain, and the several ways it legitimately
 * resolves nobody.
 *
 * <p>Mirrors {@link InternalShopStaffControllerTest}: the token check rejects
 * with a SPECIFIC 401 asserted on the status (never {@code is4xxClientError()},
 * per CLAUDE.md), and nothing downstream is touched until the shared token
 * matches. Every miss — unknown merchant, no admin on file, no account, wrong
 * role, inactive, loyalty down — is the same empty list, so this endpoint is
 * never an existence oracle for merchants or accounts.
 */
class InternalMerchantAdminControllerTest {

    private static final String TOKEN = "the-shared-secret";
    private static final UUID MERCHANT = UUID.randomUUID();
    private static final String ADMIN_EMAIL = "chipo@merchant.test";

    private UserRepository repo;
    private LoyaltyServiceClient loyalty;

    private InternalMerchantAdminController controller(String expectedToken) {
        repo = repo == null ? mock(UserRepository.class) : repo;
        loyalty = loyalty == null ? mock(LoyaltyServiceClient.class) : loyalty;
        InternalTokenAuthorizer authorizer =
                new InternalTokenAuthorizer(expectedToken, mock(AuditService.class));
        return new InternalMerchantAdminController(repo, loyalty, authorizer);
    }

    private static HttpServletRequest request() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn("/users/internal/merchants/x/admins");
        when(req.getRemoteAddr()).thenReturn("203.0.113.7");
        return req;
    }

    private static User admin(boolean active, String role) {
        return User.builder()
                .userUuid(UUID.randomUUID())
                .firstName("Chipo")
                .lastName("Moyo")
                .email(ADMIN_EMAIL)
                .password("x")
                .active(active)
                .roles(new java.util.LinkedHashSet<>(Set.of(role)))
                .build();
    }

    @SuppressWarnings("unchecked")
    private static List<InternalMerchantAdminController.MerchantAdminDTO> dataOf(ResponseEntity<?> resp) {
        return (List<InternalMerchantAdminController.MerchantAdminDTO>)
                ((ApiResult<Object>) resp.getBody()).getData();
    }

    @Test
    @DisplayName("No token: 401, and nothing downstream is asked")
    void noToken_isUnauthorized_andAsksNobody() {
        repo = mock(UserRepository.class);
        loyalty = mock(LoyaltyServiceClient.class);

        ResponseEntity<?> resp = controller(TOKEN).merchantAdmins(null, MERCHANT, request());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // The loyalty hop costs a network call — the token gate runs first.
        verify(loyalty, never()).adminEmailForMerchant(any());
        verify(repo, never()).findByEmail(anyString());
    }

    @Test
    @DisplayName("Wrong token: 401, and nothing downstream is asked")
    void wrongToken_isUnauthorized() {
        repo = mock(UserRepository.class);
        loyalty = mock(LoyaltyServiceClient.class);

        ResponseEntity<?> resp = controller(TOKEN)
                .merchantAdmins("definitely-not-it", MERCHANT, request());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(loyalty, never()).adminEmailForMerchant(any());
    }

    @Test
    @DisplayName("The chain resolves: merchant -> loyalty admin email -> the user row")
    void resolvesTheAdminUser() {
        repo = mock(UserRepository.class);
        loyalty = mock(LoyaltyServiceClient.class);
        User user = admin(true, User.Role.MERCHANT_ADMIN.name());
        when(loyalty.adminEmailForMerchant(MERCHANT)).thenReturn(Optional.of(ADMIN_EMAIL));
        when(repo.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.of(user));

        ResponseEntity<?> resp = controller(TOKEN).merchantAdmins(TOKEN, MERCHANT, request());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dataOf(resp)).singleElement().satisfies(dto -> {
            assertThat(dto.userUuid()).isEqualTo(user.getUserUuid());
            assertThat(dto.email()).isEqualTo(ADMIN_EMAIL);
        });
    }

    @Test
    @DisplayName("Loyalty knows no admin for this merchant: empty list, and no user lookup")
    void noAdminOnFile_isEmpty() {
        repo = mock(UserRepository.class);
        loyalty = mock(LoyaltyServiceClient.class);
        // Covers an unknown merchant, a merchant with a null admin_email AND a
        // loyalty outage — the client collapses all three to an empty Optional.
        when(loyalty.adminEmailForMerchant(MERCHANT)).thenReturn(Optional.empty());

        ResponseEntity<?> resp = controller(TOKEN).merchantAdmins(TOKEN, MERCHANT, request());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dataOf(resp)).isEmpty();
        verify(repo, never()).findByEmail(anyString());
    }

    @Test
    @DisplayName("The admin email has no account here yet: empty list, not an error")
    void adminEmailWithNoAccount_isEmpty() {
        repo = mock(UserRepository.class);
        loyalty = mock(LoyaltyServiceClient.class);
        when(loyalty.adminEmailForMerchant(MERCHANT)).thenReturn(Optional.of(ADMIN_EMAIL));
        when(repo.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.empty());

        ResponseEntity<?> resp = controller(TOKEN).merchantAdmins(TOKEN, MERCHANT, request());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dataOf(resp)).isEmpty();
    }

    @Test
    @DisplayName("Named as admin in loyalty but NOT a MERCHANT_ADMIN here: withheld")
    void accountWithoutTheRole_isWithheld() {
        repo = mock(UserRepository.class);
        loyalty = mock(LoyaltyServiceClient.class);
        when(loyalty.adminEmailForMerchant(MERCHANT)).thenReturn(Optional.of(ADMIN_EMAIL));
        // Sending one merchant's order detail to an account that is not a
        // merchant admin is the worse of the two failures, so this resolves
        // nobody rather than notifying them.
        when(repo.findByEmail(ADMIN_EMAIL))
                .thenReturn(Optional.of(admin(true, User.Role.CUSTOMER.name())));

        ResponseEntity<?> resp = controller(TOKEN).merchantAdmins(TOKEN, MERCHANT, request());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dataOf(resp)).isEmpty();
    }

    @Test
    @DisplayName("A deactivated admin is withheld — no order detail to a closed account")
    void inactiveAccount_isWithheld() {
        repo = mock(UserRepository.class);
        loyalty = mock(LoyaltyServiceClient.class);
        when(loyalty.adminEmailForMerchant(MERCHANT)).thenReturn(Optional.of(ADMIN_EMAIL));
        when(repo.findByEmail(ADMIN_EMAIL))
                .thenReturn(Optional.of(admin(false, User.Role.MERCHANT_ADMIN.name())));

        ResponseEntity<?> resp = controller(TOKEN).merchantAdmins(TOKEN, MERCHANT, request());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dataOf(resp)).isEmpty();
    }
}
