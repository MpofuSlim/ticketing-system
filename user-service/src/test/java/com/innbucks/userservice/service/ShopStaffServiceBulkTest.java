package com.innbucks.userservice.service;

import com.innbucks.userservice.dto.BulkShopUserResultDTO;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.event.CredentialDeliveryRequested;
import com.innbucks.userservice.integration.LoyaltyServiceClient;
import com.innbucks.userservice.repository.UserRepository;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for {@link ShopStaffService#bulkImportShopUsersCsv} — the batch
 * SHOP_USER import. The real per-row {@code createShopUser} runs against mocked
 * repositories (the self-proxy is wired to the instance under test), so these
 * exercise the actual creation path, not a stub of it.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ShopStaffServiceBulkTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private LoyaltyServiceClient loyaltyServiceClient;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private ObjectProvider<ShopStaffService> selfProvider;

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private ShopStaffService service;

    private static final UUID SHOP = UUID.randomUUID();
    private static final UUID MERCHANT = UUID.randomUUID();

    private static final String HEADER = "firstName,middleName,lastName,email,phoneNumber\n";

    @BeforeEach
    void setUp() {
        service = new ShopStaffService(userRepository, passwordEncoder, loyaltyServiceClient,
                eventPublisher, validator, selfProvider);
        ReflectionTestUtils.setField(service, "deploymentCountry", "ZW");
        // The bulk method calls createShopUser through the proxy; in the unit
        // test the "proxy" is the instance itself, so the real per-row logic runs.
        when(selfProvider.getObject()).thenReturn(service);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAsShopAdmin(UUID shopId, UUID merchantId) {
        User admin = User.builder().email("admin@shop.co.zw")
                .roles(User.roleNames(User.Role.SHOP_ADMIN))
                .loyaltyShopId(shopId).loyaltyMerchantId(merchantId).build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(admin.getEmail(), null));
        when(userRepository.findByEmail(admin.getEmail())).thenReturn(Optional.of(admin));
    }

    @Test
    void createsEveryValidRow_andDeliversCredentials() {
        authenticateAsShopAdmin(SHOP, MERCHANT);
        String csv = HEADER
                + "Rufaro,T,Ncube,rufaro@shop.co.zw,+263772345678\n"
                + "Tanaka,,Moyo,tanaka@shop.co.zw,+263772345679\n";

        BulkShopUserResultDTO result = service.bulkImportShopUsersCsv(csv);

        assertThat(result.total()).isEqualTo(2);
        assertThat(result.created()).isEqualTo(2);
        assertThat(result.failed()).isZero();
        assertThat(result.results()).extracting(BulkShopUserResultDTO.RowResult::status)
                .containsExactly("CREATED", "CREATED");
        // Line numbers reflect the file (header is line 1).
        assertThat(result.results()).extracting(BulkShopUserResultDTO.RowResult::line)
                .containsExactly(2, 3);
        // Two users saved, two onboarding credential deliveries dispatched.
        verify(userRepository, times(2)).save(any(User.class));
        verify(eventPublisher, times(2)).publishEvent(any(CredentialDeliveryRequested.class));
    }

    @Test
    void duplicateEmailRow_failsOnlyThatRow() {
        authenticateAsShopAdmin(SHOP, MERCHANT);
        when(userRepository.existsByEmail("dupe@shop.co.zw")).thenReturn(true);
        String csv = HEADER
                + "Rufaro,T,Ncube,rufaro@shop.co.zw,+263772345678\n"
                + "Dupe,,User,dupe@shop.co.zw,+263772345679\n";

        BulkShopUserResultDTO result = service.bulkImportShopUsersCsv(csv);

        assertThat(result.created()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        BulkShopUserResultDTO.RowResult bad = result.results().get(1);
        assertThat(bad.status()).isEqualTo("FAILED");
        assertThat(bad.email()).isEqualTo("dupe@shop.co.zw");
        assertThat(bad.error()).isEqualTo("Email already registered");
        // The good row still committed its credential delivery; the bad row didn't.
        verify(eventPublisher, times(1)).publishEvent(any(CredentialDeliveryRequested.class));
    }

    @Test
    void invalidRow_failsBeanValidation_withoutHittingCreate() {
        authenticateAsShopAdmin(SHOP, MERCHANT);
        // Missing firstName and a malformed email — both @NotBlank/@Email violations.
        String csv = HEADER
                + ",,Ncube,not-an-email,+263772345678\n";

        BulkShopUserResultDTO result = service.bulkImportShopUsersCsv(csv);

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.results().get(0).error())
                .contains("email").contains("firstName");
        // Validation short-circuits before any persistence.
        verify(userRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void tolerantHeader_casingSpacingAndOptionalMiddleName() {
        authenticateAsShopAdmin(SHOP, MERCHANT);
        // Reordered, spaced, mixed-case header, and no middleName column at all.
        String csv = "Email, First Name , Last Name , Phone Number\n"
                + "rufaro@shop.co.zw,Rufaro,Ncube,+263772345678\n";

        BulkShopUserResultDTO result = service.bulkImportShopUsersCsv(csv);

        assertThat(result.created()).isEqualTo(1);
        assertThat(result.results().get(0).email()).isEqualTo("rufaro@shop.co.zw");
    }

    @Test
    void notShopAdmin_rejectedOnceWith403_beforeParsing() {
        User merchantAdmin = User.builder().email("merchant@x.co.zw")
                .roles(User.roleNames(User.Role.MERCHANT_ADMIN))
                .loyaltyMerchantId(MERCHANT).build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(merchantAdmin.getEmail(), null));
        when(userRepository.findByEmail(merchantAdmin.getEmail())).thenReturn(Optional.of(merchantAdmin));

        assertThatThrownBy(() -> service.bulkImportShopUsersCsv(HEADER + "A,,B,a@x.com,+263772345678\n"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(rse.getReason()).isEqualTo("Only SHOP_ADMIN can create shop users");
                });
        verify(userRepository, never()).save(any());
    }

    @Test
    void shopAdminWithoutScope_rejectedWith400() {
        authenticateAsShopAdmin(null, null); // SHOP_ADMIN role but no shop/merchant binding

        assertThatThrownBy(() -> service.bulkImportShopUsersCsv(HEADER + "A,,B,a@x.com,+263772345678\n"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void missingRequiredColumn_rejectedWith400() {
        authenticateAsShopAdmin(SHOP, MERCHANT);
        // No email column.
        String csv = "firstName,lastName,phoneNumber\nA,B,+263772345678\n";

        assertThatThrownBy(() -> service.bulkImportShopUsersCsv(csv))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(rse.getReason()).contains("email");
                });
    }

    @Test
    void headerButNoDataRows_rejectedWith400() {
        authenticateAsShopAdmin(SHOP, MERCHANT);

        assertThatThrownBy(() -> service.bulkImportShopUsersCsv(HEADER))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(rse.getReason()).contains("no data rows");
                });
    }

    @Test
    void blankInteriorAndTrailingLinesAreSkipped() {
        authenticateAsShopAdmin(SHOP, MERCHANT);
        String csv = HEADER
                + "Rufaro,T,Ncube,rufaro@shop.co.zw,+263772345678\n"
                + "\n"
                + "Tanaka,,Moyo,tanaka@shop.co.zw,+263772345679\n"
                + "\n";

        BulkShopUserResultDTO result = service.bulkImportShopUsersCsv(csv);

        assertThat(result.total()).isEqualTo(2);
        assertThat(result.created()).isEqualTo(2);
    }
}
