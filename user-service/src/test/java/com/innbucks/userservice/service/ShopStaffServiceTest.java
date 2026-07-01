package com.innbucks.userservice.service;

import com.innbucks.userservice.dto.CreateShopAdminDTO;
import com.innbucks.userservice.dto.CreateShopUserDTO;
import com.innbucks.userservice.dto.UserResponseDTO;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.event.CredentialDeliveryRequested;
import com.innbucks.userservice.integration.LoyaltyServiceClient;
import com.innbucks.userservice.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ShopStaffService}'s onboarding-notification wiring.
 *
 * <p>Pins the behaviour added when the email channel was wired in: a newly
 * created SHOP_ADMIN / SHOP_USER is emailed their credentials (email is the
 * primary channel — staff always onboard with an address), SMS is the
 * fallback when the email gateway rejects the message, and a delivery failure
 * on BOTH channels must never roll back or fail the creation (best-effort,
 * mirroring {@code UserAdminService#notifyApproval}).
 *
 * <p>Pure Mockito, no Spring context — the {@code @Value} deployment-country
 * field is set via reflection since it isn't a constructor dependency.
 */
@ExtendWith(MockitoExtension.class)
class ShopStaffServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private LoyaltyServiceClient loyaltyServiceClient;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks private ShopStaffService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "deploymentCountry", "ZW");
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(User caller) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(caller.getEmail(), null));
        when(userRepository.findByEmail(caller.getEmail())).thenReturn(Optional.of(caller));
    }

    private User merchantAdmin(UUID merchantId) {
        return User.builder().email("merchant@x.com")
                .roles(EnumSet.of(User.Role.MERCHANT_ADMIN))
                .loyaltyMerchantId(merchantId).build();
    }

    private CreateShopAdminDTO shopAdminDto(UUID shopId) {
        CreateShopAdminDTO dto = new CreateShopAdminDTO();
        dto.setFirstName("Tendai");
        dto.setLastName("Moyo");
        dto.setEmail("tendai@shop.co.zw");
        dto.setPhoneNumber("+263771234567");
        dto.setShopId(shopId);
        return dto;
    }

    private void stubShopAdminHappyPath(UUID shopId, UUID merchantId) {
        when(loyaltyServiceClient.findShop(shopId)).thenReturn(Optional.of(
                new LoyaltyServiceClient.ShopLookupResponse(
                        shopId.toString(), merchantId.toString(), "tenant-1", "ACTIVE")));
        when(userRepository.existsByEmail("tendai@shop.co.zw")).thenReturn(false);
        when(userRepository.existsByPhoneNumberAndHomeCountry("+263771234567", "ZW")).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");
    }

    @Test
    void createShopAdmin_publishesOnboardingEvent_offTheRequestThread() {
        UUID shopId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        authenticateAs(merchantAdmin(merchantId));
        stubShopAdminHappyPath(shopId, merchantId);

        UserResponseDTO result = service.createShopAdmin(shopAdminDto(shopId));
        assertThat(result).isNotNull();

        // The temp password is a per-user random value (not the old shared
        // #Pass123). Capture what was hashed and assert the SAME value is carried
        // on the delivery event.
        ArgumentCaptor<String> pw = ArgumentCaptor.forClass(String.class);
        verify(passwordEncoder).encode(pw.capture());
        String generated = pw.getValue();
        assertThat(generated).isNotEqualTo("#Pass123")
                .matches("[A-Za-z0-9]{5}-[A-Za-z0-9]{5}");

        // Credentials are delivered off-thread by the async CredentialDeliveryListener
        // (email -> SMS -> WhatsApp), so the create response never blocks on the
        // notification gateway — the service only publishes the event.
        ArgumentCaptor<CredentialDeliveryRequested> ev =
                ArgumentCaptor.forClass(CredentialDeliveryRequested.class);
        verify(eventPublisher).publishEvent(ev.capture());
        CredentialDeliveryRequested e = ev.getValue();
        assertThat(e.reason()).isEqualTo(CredentialDeliveryRequested.Reason.ONBOARDING);
        assertThat(e.email()).isEqualTo("tendai@shop.co.zw");
        assertThat(e.firstName()).isEqualTo("Tendai");
        assertThat(e.phoneNumber()).isEqualTo("+263771234567");
        assertThat(e.tempPassword()).isEqualTo(generated);
    }

    @Test
    void createShopUser_publishesOnboardingEvent() {
        UUID shopId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        authenticateAs(User.builder().email("shopadmin@x.com")
                .roles(EnumSet.of(User.Role.SHOP_ADMIN))
                .loyaltyShopId(shopId).loyaltyMerchantId(merchantId).build());
        when(userRepository.existsByEmail("rufaro@shop.co.zw")).thenReturn(false);
        when(userRepository.existsByPhoneNumberAndHomeCountry("+263772345678", "ZW")).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");

        CreateShopUserDTO dto = new CreateShopUserDTO();
        dto.setFirstName("Rufaro");
        dto.setLastName("Ncube");
        dto.setEmail("rufaro@shop.co.zw");
        dto.setPhoneNumber("+263772345678");

        service.createShopUser(dto);

        ArgumentCaptor<CredentialDeliveryRequested> ev =
                ArgumentCaptor.forClass(CredentialDeliveryRequested.class);
        verify(eventPublisher).publishEvent(ev.capture());
        assertThat(ev.getValue().reason()).isEqualTo(CredentialDeliveryRequested.Reason.ONBOARDING);
        assertThat(ev.getValue().email()).isEqualTo("rufaro@shop.co.zw");
    }

    private User shopUser(UUID userUuid, UUID merchantId, UUID shopId) {
        return User.builder()
                .id(74L).userUuid(userUuid)
                .email("rufaro@pizza.co.zw").firstName("Rufaro").lastName("Ncube")
                .phoneNumber("+263772345678")
                .roles(EnumSet.of(User.Role.SHOP_USER))
                .loyaltyMerchantId(merchantId).loyaltyShopId(shopId)
                .active(true).build();
    }

    private User shopAdminTarget(UUID userUuid, UUID merchantId, UUID shopId) {
        return User.builder()
                .id(73L).userUuid(userUuid)
                .email("tendai@pizza.co.zw").firstName("Tendai").lastName("Moyo")
                .phoneNumber("+263771234567")
                .roles(EnumSet.of(User.Role.SHOP_ADMIN))
                .loyaltyMerchantId(merchantId).loyaltyShopId(shopId)
                .active(true).build();
    }

    @Test
    void resetTemporaryPassword_shopAdminMayResetShopUserAtSameShop() {
        UUID shopId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        UUID targetUuid = UUID.randomUUID();
        User caller = User.builder().id(1L).email("shopadmin@x.com")
                .roles(EnumSet.of(User.Role.SHOP_ADMIN))
                .loyaltyMerchantId(merchantId).loyaltyShopId(shopId).build();
        authenticateAs(caller);
        User target = shopUser(targetUuid, merchantId, shopId);
        when(userRepository.findByUserUuid(targetUuid)).thenReturn(Optional.of(target));
        when(passwordEncoder.encode(any())).thenReturn("NEW_HASH");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        UserResponseDTO result = service.resetTemporaryPassword(targetUuid);

        assertThat(result.getUserUuid()).isEqualTo(targetUuid);
        assertThat(target.getPassword()).isEqualTo("NEW_HASH");
        assertThat(target.isMustChangePassword()).isTrue();
        ArgumentCaptor<CredentialDeliveryRequested> ev =
                ArgumentCaptor.forClass(CredentialDeliveryRequested.class);
        verify(eventPublisher).publishEvent(ev.capture());
        assertThat(ev.getValue().reason()).isEqualTo(CredentialDeliveryRequested.Reason.RESET);
        assertThat(ev.getValue().email()).isEqualTo("rufaro@pizza.co.zw");
    }

    @Test
    void resetTemporaryPassword_merchantAdminMayResetShopAdminAtSameMerchant() {
        UUID merchantId = UUID.randomUUID();
        UUID targetShopId = UUID.randomUUID();
        UUID targetUuid = UUID.randomUUID();
        User caller = User.builder().id(1L).email("merchantadmin@x.com")
                .roles(EnumSet.of(User.Role.MERCHANT_ADMIN))
                .loyaltyMerchantId(merchantId).build();
        authenticateAs(caller);
        User target = shopAdminTarget(targetUuid, merchantId, targetShopId);
        when(userRepository.findByUserUuid(targetUuid)).thenReturn(Optional.of(target));
        when(passwordEncoder.encode(any())).thenReturn("NEW_HASH");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        UserResponseDTO result = service.resetTemporaryPassword(targetUuid);

        assertThat(result.getUserUuid()).isEqualTo(targetUuid);
        ArgumentCaptor<CredentialDeliveryRequested> ev =
                ArgumentCaptor.forClass(CredentialDeliveryRequested.class);
        verify(eventPublisher).publishEvent(ev.capture());
        assertThat(ev.getValue().reason()).isEqualTo(CredentialDeliveryRequested.Reason.RESET);
        assertThat(ev.getValue().email()).isEqualTo("tendai@pizza.co.zw");
    }

    @Test
    void resetTemporaryPassword_shopAdminCannotResetShopUserAtDifferentShop() {
        UUID merchantId = UUID.randomUUID();
        UUID callerShopId = UUID.randomUUID();
        UUID otherShopId = UUID.randomUUID();
        UUID targetUuid = UUID.randomUUID();
        User caller = User.builder().id(1L).email("shopadmin@x.com")
                .roles(EnumSet.of(User.Role.SHOP_ADMIN))
                .loyaltyMerchantId(merchantId).loyaltyShopId(callerShopId).build();
        authenticateAs(caller);
        // Target is a SHOP_USER at a DIFFERENT shop within the same merchant.
        when(userRepository.findByUserUuid(targetUuid))
                .thenReturn(Optional.of(shopUser(targetUuid, merchantId, otherShopId)));

        assertThatThrownBy(() -> service.resetTemporaryPassword(targetUuid))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
        verify(passwordEncoder, never()).encode(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void resetTemporaryPassword_shopAdminCannotResetPeerShopAdmin() {
        // SHOP_ADMINs are peers; only the MERCHANT_ADMIN above them can reset.
        UUID merchantId = UUID.randomUUID();
        UUID shopId = UUID.randomUUID();
        UUID peerUuid = UUID.randomUUID();
        User caller = User.builder().id(1L).email("shopadmin@x.com")
                .roles(EnumSet.of(User.Role.SHOP_ADMIN))
                .loyaltyMerchantId(merchantId).loyaltyShopId(shopId).build();
        authenticateAs(caller);
        when(userRepository.findByUserUuid(peerUuid))
                .thenReturn(Optional.of(shopAdminTarget(peerUuid, merchantId, shopId)));

        assertThatThrownBy(() -> service.resetTemporaryPassword(peerUuid))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void resetTemporaryPassword_merchantAdminCannotResetStaffAtDifferentMerchant() {
        UUID callerMerchant = UUID.randomUUID();
        UUID otherMerchant = UUID.randomUUID();
        UUID targetUuid = UUID.randomUUID();
        User caller = User.builder().id(1L).email("merchant@x.com")
                .roles(EnumSet.of(User.Role.MERCHANT_ADMIN))
                .loyaltyMerchantId(callerMerchant).build();
        authenticateAs(caller);
        when(userRepository.findByUserUuid(targetUuid))
                .thenReturn(Optional.of(shopUser(targetUuid, otherMerchant, UUID.randomUUID())));

        assertThatThrownBy(() -> service.resetTemporaryPassword(targetUuid))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    // --- H2: cross-merchant scoping on the admin create / list endpoints -----

    @Test
    void createShopAdmin_rejectsShopBelongingToAnotherMerchant() {
        UUID shopId = UUID.randomUUID();
        UUID callerMerchant = UUID.randomUUID();
        UUID foreignMerchant = UUID.randomUUID();
        authenticateAs(merchantAdmin(callerMerchant));
        // The shop resolves to a DIFFERENT merchant than the caller's.
        when(loyaltyServiceClient.findShop(shopId)).thenReturn(Optional.of(
                new LoyaltyServiceClient.ShopLookupResponse(
                        shopId.toString(), foreignMerchant.toString(), "tenant-2", "ACTIVE")));

        assertThatThrownBy(() -> service.createShopAdmin(shopAdminDto(shopId)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
        // No staff account is provisioned into a cross-merchant shop.
        verify(userRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void listForMerchant_rejectsForeignMerchant_andReadsNoStaff() {
        UUID callerMerchant = UUID.randomUUID();
        UUID foreignMerchant = UUID.randomUUID();
        authenticateAs(merchantAdmin(callerMerchant));

        assertThatThrownBy(() -> service.listForMerchant(foreignMerchant))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
        verify(userRepository, never()).findByLoyaltyMerchantId(any());
    }

    @Test
    void listForMerchant_returnsStaff_forOwnMerchant() {
        UUID merchantId = UUID.randomUUID();
        authenticateAs(merchantAdmin(merchantId));
        when(userRepository.findByLoyaltyMerchantId(merchantId)).thenReturn(List.of());

        assertThatCode(() -> service.listForMerchant(merchantId)).doesNotThrowAnyException();
        verify(userRepository).findByLoyaltyMerchantId(merchantId);
    }

    @Test
    void listForShop_rejectsShopBelongingToAnotherMerchant_andReadsNoStaff() {
        UUID shopId = UUID.randomUUID();
        UUID callerMerchant = UUID.randomUUID();
        UUID foreignMerchant = UUID.randomUUID();
        authenticateAs(merchantAdmin(callerMerchant));
        when(loyaltyServiceClient.findShop(shopId)).thenReturn(Optional.of(
                new LoyaltyServiceClient.ShopLookupResponse(
                        shopId.toString(), foreignMerchant.toString(), "tenant-2", "ACTIVE")));

        assertThatThrownBy(() -> service.listForShop(shopId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
        verify(userRepository, never()).findByLoyaltyShopId(any());
    }

    // --- Merchant-admin authority resolved by admin email (the actual fix) ----
    // Production shape: a MERCHANT_ADMIN carries NO loyaltyMerchantId on their
    // JWT or User row — their merchant(s) are resolved from loyalty-service by
    // admin email. Before the fix, every one of these returned 403 because the
    // code read the (always-null) caller.getLoyaltyMerchantId().

    private User merchantAdminNoBinding(String email) {
        return User.builder().email(email)
                .roles(EnumSet.of(User.Role.MERCHANT_ADMIN))
                .build();
    }

    @Test
    void listForShop_merchantAdminWithoutLocalBinding_resolvesMerchantByEmail_allowsOwnShop() {
        UUID shopId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        authenticateAs(merchantAdminNoBinding("merchant@x.com"));
        when(loyaltyServiceClient.merchantIdsForAdmin("merchant@x.com"))
                .thenReturn(List.of(merchantId));
        when(loyaltyServiceClient.findShop(shopId)).thenReturn(Optional.of(
                new LoyaltyServiceClient.ShopLookupResponse(
                        shopId.toString(), merchantId.toString(), "tenant-1", "ACTIVE")));
        when(userRepository.findByLoyaltyShopId(shopId)).thenReturn(List.of());

        assertThatCode(() -> service.listForShop(shopId)).doesNotThrowAnyException();
        verify(userRepository).findByLoyaltyShopId(shopId);
    }

    @Test
    void listForShop_merchantAdminWithoutLocalBinding_foreignShop_forbidden() {
        UUID shopId = UUID.randomUUID();
        UUID ownedMerchant = UUID.randomUUID();
        UUID foreignMerchant = UUID.randomUUID();
        authenticateAs(merchantAdminNoBinding("merchant@x.com"));
        when(loyaltyServiceClient.merchantIdsForAdmin("merchant@x.com"))
                .thenReturn(List.of(ownedMerchant));
        when(loyaltyServiceClient.findShop(shopId)).thenReturn(Optional.of(
                new LoyaltyServiceClient.ShopLookupResponse(
                        shopId.toString(), foreignMerchant.toString(), "tenant-2", "ACTIVE")));

        assertThatThrownBy(() -> service.listForShop(shopId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
        verify(userRepository, never()).findByLoyaltyShopId(any());
    }

    @Test
    void listForMerchant_merchantAdminWithoutLocalBinding_resolvesOwnershipByEmail() {
        UUID merchantId = UUID.randomUUID();
        authenticateAs(merchantAdminNoBinding("merchant@x.com"));
        when(loyaltyServiceClient.merchantIdsForAdmin("merchant@x.com"))
                .thenReturn(List.of(merchantId));
        when(userRepository.findByLoyaltyMerchantId(merchantId)).thenReturn(List.of());

        assertThatCode(() -> service.listForMerchant(merchantId)).doesNotThrowAnyException();
        verify(userRepository).findByLoyaltyMerchantId(merchantId);
    }

    @Test
    void listForCallerShop_merchantAdmin_returnsStaffAcrossAllTheirMerchants() {
        UUID m1 = UUID.randomUUID();
        UUID m2 = UUID.randomUUID();
        authenticateAs(merchantAdminNoBinding("merchant@x.com"));
        when(loyaltyServiceClient.merchantIdsForAdmin("merchant@x.com"))
                .thenReturn(List.of(m1, m2));
        when(userRepository.findByLoyaltyMerchantId(m1))
                .thenReturn(List.of(shopAdminTarget(UUID.randomUUID(), m1, UUID.randomUUID())));
        when(userRepository.findByLoyaltyMerchantId(m2))
                .thenReturn(List.of(shopUser(UUID.randomUUID(), m2, UUID.randomUUID())));

        List<UserResponseDTO> staff = service.listForCallerShop();

        assertThat(staff).hasSize(2);
        verify(userRepository, never()).findByLoyaltyShopId(any());
    }

    @Test
    void listForCallerShop_merchantAdminOwningNothing_returnsEmpty() {
        authenticateAs(merchantAdminNoBinding("merchant@x.com"));
        when(loyaltyServiceClient.merchantIdsForAdmin("merchant@x.com")).thenReturn(List.of());

        assertThat(service.listForCallerShop()).isEmpty();
        verify(userRepository, never()).findByLoyaltyMerchantId(any());
        verify(userRepository, never()).findByLoyaltyShopId(any());
    }

    @Test
    void listForCallerShop_shopAdmin_stillReturnsOwnShopStaff() {
        UUID shopId = UUID.randomUUID();
        authenticateAs(User.builder().email("shopadmin@x.com")
                .roles(EnumSet.of(User.Role.SHOP_ADMIN))
                .loyaltyShopId(shopId).build());
        when(userRepository.findByLoyaltyShopId(shopId))
                .thenReturn(List.of(shopUser(UUID.randomUUID(), UUID.randomUUID(), shopId)));

        List<UserResponseDTO> staff = service.listForCallerShop();

        assertThat(staff).hasSize(1);
        verify(loyaltyServiceClient, never()).merchantIdsForAdmin(any());
    }
}
